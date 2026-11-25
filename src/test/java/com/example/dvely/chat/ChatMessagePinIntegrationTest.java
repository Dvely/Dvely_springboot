package com.example.dvely.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.chat.application.pin.PreviewPinInput;
import com.example.dvely.chat.application.pin.PreviewPinService;
import com.example.dvely.chat.application.query.ChatQueryService;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.model.ChatMessagePin;
import com.example.dvely.chat.domain.model.ChatMessagePinLabel;
import com.example.dvely.chat.domain.repository.ChatMessagePinRepository;
import com.example.dvely.support.JdbcRows;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 프리뷰에서 짚은 곳(설계 §16.6)을 실 MySQL 로 본다: 테이블(Flyway), 저장·칩 조회·태스크로 찾기, 메시지·대화를 지우면 함께
 * 지워지는 것, 그리고 형식이 틀린 짚기가 메시지도 태스크도 남기지 않는 것.
 */
@SpringBootTest
class ChatMessagePinIntegrationTest {

    private static final String MARK = "pin575-";

    @Autowired private ChatMessagePinRepository pins;
    @Autowired private PreviewPinService previewPins;
    @Autowired private ChatCommandService commands;
    @Autowired private ChatQueryService queries;
    @Autowired private AgentMessageService agentMessages;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserWithdrawalService withdrawalService;

    private long userId;
    private long conversation;

    @BeforeEach
    void setUp() {
        cleanUp();
        userId = JdbcRows.insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        long projectId = JdbcRows.insert(jdbc, "insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "p");
        conversation = JdbcRows.insert(jdbc, "insert into chat_sessions (user_id, project_id) values (?, ?)", userId, projectId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void pinsAreStoredReadAsChipsAndFoundByTheTaskTheMessageStarted() {
        long first = message("버튼을 키워줘", "task-" + MARK + "1");
        long second = message("그냥 대화", null);
        List<ChatMessagePin> saved = previewPins.normalize(List.of(button(null), screen()));
        pins.saveAll(first, saved);

        assertThat(pins.findByMessageId(first)).extracting(ChatMessagePin::position).containsExactly(1, 2);
        assertThat(pins.findLabelsByMessageIds(List.of(first, second)))
                .containsOnlyKeys(first)
                .extractingByKey(first).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(new ChatMessagePinLabel(1, saved.get(0).label()), new ChatMessagePinLabel(2, "화면 전체"));
        assertThat(pins.findByTaskId("task-" + MARK + "1")).hasSize(2);
        assertThat(pins.findByTaskId("task-" + MARK + "none")).isEmpty();
        assertThat(previewPins.promptForTask("task-" + MARK + "1")).get().asString()
                .contains("짚은 곳 2개").contains("#profile > button.save");
    }

    @Test
    void theMessageListCarriesChipsAndThePlanSeesTheBlock() {
        long pinned = message("이 버튼을 더 크게", "task-" + MARK + "2");
        pins.saveAll(pinned, previewPins.normalize(List.of(button(null))));

        MessageResult listed = queries.getMessages(userId, conversation).stream()
                .filter(m -> m.messageId() == pinned).findFirst().orElseThrow();
        assertThat(listed.pins()).hasSize(1);
        assertThat(agentMessages.getUserIntentHistory(conversation)).last()
                .extracting(m -> m.content()).asString()
                .startsWith("[지금 처리할 요청]\n이 버튼을 더 크게\n\n## 프리뷰에서 짚은 곳");
    }

    @Test
    void deletingTheConversationDeletesItsPins() {
        long pinned = message("버튼", null);
        pins.saveAll(pinned, previewPins.normalize(List.of(button(null))));

        jdbc.update("delete from chat_sessions where chat_session_id = ?", conversation);

        assertThat(jdbc.queryForObject("select count(*) from chat_message_pins where message_id = ?", Integer.class, pinned))
                .isZero();
    }

    @Test
    void aMalformedPinLeavesNeitherAMessageNorATask() {
        Integer before = jdbc.queryForObject("select count(*) from chat_messages where chat_session_id = ?", Integer.class, conversation);

        assertThatThrownBy(() -> commands.sendMessage(userId, conversation, "버튼 키워줘", AiProvider.CLAUDE_CODE,
                List.of(new PreviewPinInput(Map.of("probeId", "bogus"), null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("select count(*) from chat_messages where chat_session_id = ?", Integer.class, conversation))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from agent_runs where chat_session_id = ?", Integer.class, conversation))
                .isZero();
    }

    private long message(String content, String taskId) {
        return JdbcRows.insert(jdbc, "insert into chat_messages (chat_session_id, role, content, token_count, task_id) values (?, 'user', ?, 0, ?)",
                conversation, content, taskId);
    }

    private static PreviewPinInput button(Integer scope) {
        return new PreviewPinInput(Map.of(
                "probeId", "p1",
                "ok", true,
                "point", Map.of("x", 0.317, "y", 0.234),
                "screen", Map.of("label", "/settings", "title", "Acme 설정"),
                "element", Map.of("type", "button", "text", "저장", "selector", "#profile > button.save",
                        "bounds", Map.of("x", 0.28, "y", 0.2, "w", 0.07, "h", 0.06)),
                "scopes", List.of(
                        Map.of("kind", "element", "type", "button", "text", "저장", "selector", "#profile > button.save",
                                "bounds", Map.of("x", 0.28, "y", 0.2, "w", 0.07, "h", 0.06)),
                        Map.of("kind", "screen", "type", "screen", "bounds", Map.of("x", 0, "y", 0, "w", 1, "h", 1))),
                "defaultScope", 0), scope);
    }

    private static PreviewPinInput screen() {
        return new PreviewPinInput(Map.of(
                "probeId", "p2", "ok", true,
                "scopes", List.of(Map.of("kind", "screen", "type", "screen", "bounds", Map.of("x", 0, "y", 0, "w", 1, "h", 1))),
                "defaultScope", 0), null);
    }
}
