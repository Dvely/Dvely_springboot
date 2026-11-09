package com.example.dvely.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import com.example.dvely.support.SentSql;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 대화 메시지를 읽는 쿼리를 실 MySQL 로 본다(#625).
 *
 * <p>LLM 대화 창은 이제 DB 에서 마지막 몇 건만 읽는다. 자바에서 거르던 "사용자 발화만" 도 쿼리로 옮겼으므로, 그 조건이
 * 맞는지는 목이 아니라 여기서 확인한다.</p>
 *
 * <p>계획도 본다. 인덱스와 정렬은 {@code SchemaTest} 도 {@code ddl-auto: validate} 도 보지 않아, 틀려도 다른 테스트가
 * 통과한다. 다른 대화의 행을 사이에 끼워 넣어 "대화가 쉬었다 이어진" 모양을 만든다 — 그때 {@code ORDER BY message_id} 는
 * PK 를 골라 그 사이 행을 다 훑었다(같은 모양의 임시 테이블 실측: 커서 다음 25건에 5,025행).</p>
 *
 * <p>정렬은 계획이 아니라 <b>보낸 SQL</b> 로 고정한다. 여러 테스트가 쓰고 지운 공유 테스트 DB 의 통계로는 옵티마이저가
 * {@code ORDER BY message_id} 에도 대화 인덱스를 고르기도 해, 계획만으로는 정렬이 되돌아간 것을 놓친다(변이로 확인).</p>
 */
@SpringBootTest
class ChatMessageWindowQueryIntegrationTest {

    private static final String MARK = "cw625-";

    /**
     * 정렬 키 앞에 대화 id 가 있어야 PK 가 그 순서를 줄 수 없어 옵티마이저가 대화 인덱스를 고른다
     * ({@code SpringDataChatMessageRepository} 참고). 별칭은 Hibernate 가 정한다.
     */
    private static final java.util.regex.Pattern ORDER_BY_CONVERSATION_THEN_ID =
            java.util.regex.Pattern.compile("order by \\w+\\.chat_session_id( desc)?,\\s*\\w+\\.message_id");

    @Autowired private ChatMessageRepository messages;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserWithdrawalService withdrawalService;

    private long conversation;
    private long other;

    @BeforeEach
    void setUp() {
        cleanUp();
        long userId = insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        long projectId = insert("insert into projects (user_id, project_name) values (?, ?)", userId, MARK + "p");
        conversation = insert("insert into chat_sessions (user_id, project_id) values (?, ?)", userId, projectId);
        other = insert("insert into chat_sessions (user_id, project_id) values (?, ?)", userId, projectId);
        // 이 대화가 500개를 쓰고, 쉬는 동안 다른 대화가 5,000개를 쓰고, 다시 500개를 쓴다. 이 대화가 표의 큰 몫이라야
        // ORDER BY message_id LIMIT n 에서 옵티마이저가 PK 를 고른다 — 작은 데이터로는 그 함정이 재현되지 않는다(실측)
        write(conversation, "early", 500);
        write(other, "other", 5_000);
        write(conversation, "late", 500);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : jdbc.queryForList("select user_id from users where github_user_id like ?", Long.class, MARK + "%")) {
            withdrawalService.withdraw(id);
        }
    }

    @Test
    void theWindowIsTheLastMessagesInWrittenOrder() {
        List<ChatMessage> latest = messages.findLatestByConversationId(conversation, 20);

        assertThat(latest).extracting(ChatMessage::getContent)
                .containsExactlyElementsOf(range("late", 480, 500));
        assertThat(latest).extracting(ChatMessage::getConversationId).containsOnly(conversation);
    }

    @Test
    void theUserWindowHasUserMessagesOnly() {
        // 계획 수립 경로는 사용자 발화만 싣는다 — 우리가 쓴 운영 안내(어시스턴트 발화)를 모델이 흉내 내지 않게
        List<ChatMessage> latest = messages.findLatestByConversationIdAndRole(conversation, ChatRole.USER, 20);

        assertThat(latest).extracting(ChatMessage::getRole).containsOnly(ChatRole.USER);
        // 짝수 번째가 사용자 발화다
        assertThat(latest).extracting(ChatMessage::getContent).containsExactlyElementsOf(evens("late", 460, 500));
    }

    @Test
    void aWindowLargerThanTheConversationReturnsAllOfIt() {
        assertThat(messages.findLatestByConversationId(conversation, 5_000)).hasSize(1_000);
        assertThat(messages.findLatestByConversationId(other, 1)).extracting(ChatMessage::getContent)
                .containsExactly("other-4999");
    }

    @Test
    void pagesStillComeInWrittenOrderAcrossTheGap() {
        List<ChatMessage> first = messages.findPageByConversationId(conversation, null, 25);
        List<ChatMessage> next = messages.findPageByConversationId(conversation, first.getLast().getId(), 25);
        List<ChatMessage> pastTheGap = messages.findPageByConversationId(conversation, lastEarlyId(), 10);

        assertThat(first).extracting(ChatMessage::getContent).containsExactlyElementsOf(range("early", 0, 25));
        assertThat(next).extracting(ChatMessage::getContent).containsExactlyElementsOf(range("early", 25, 50));
        assertThat(pastTheGap).extracting(ChatMessage::getContent).containsExactlyElementsOf(range("late", 0, 10));
    }

    @Test
    void thePagePastTheGapReadsTheConversationIndexNotThePrimaryKey() {
        Long cursor = lastEarlyId();

        // 손으로 옮긴 SQL 이 아니라 Hibernate 가 실제로 보낸 SQL 을 EXPLAIN 한다 — JPQL 의 정렬이 되돌아가도 여기서 걸린다
        String sql = sentSql(() -> messages.findPageByConversationId(conversation, cursor, 25));
        Map<String, Object> plan = explain(sql, conversation, cursor, cursor, 25);

        assertThat(plan.get("key")).as(sql).isEqualTo("idx_chat_messages_session_message");
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("filesort");
        assertThat(sql).containsPattern(ORDER_BY_CONVERSATION_THEN_ID);
    }

    @Test
    void theWindowIsReadBackwardsOnTheConversationIndexWithoutSorting() {
        String all = sentSql(() -> messages.findLatestByConversationId(conversation, 20));
        String users = sentSql(() -> messages.findLatestByConversationIdAndRole(conversation, ChatRole.USER, 20));

        Map<String, Object> allPlan = explain(all, conversation, 20);
        Map<String, Object> userPlan = explain(users, conversation, "user", 20);

        assertThat(allPlan.get("key")).as(all).isEqualTo("idx_chat_messages_session_message");
        assertThat(String.valueOf(allPlan.get("Extra"))).doesNotContain("filesort");
        assertThat(userPlan.get("key")).as(users).isEqualTo("idx_chat_messages_session_message");
        assertThat(String.valueOf(userPlan.get("Extra"))).doesNotContain("filesort");
        assertThat(all).containsPattern(ORDER_BY_CONVERSATION_THEN_ID);
        assertThat(users).containsPattern(ORDER_BY_CONVERSATION_THEN_ID);
    }

    private Long lastEarlyId() {
        return jdbc.queryForObject("select max(message_id) from chat_messages where chat_session_id = ? and content like 'early-%'",
                Long.class, conversation);
    }

    /** {@code call} 이 보낸 select 한 문장. */
    private String sentSql(Runnable call) {
        List<String> selects = SentSql.capture(call).stream().filter(sql -> sql.startsWith("select")).toList();
        assertThat(selects).as("보낸 select").hasSize(1);
        return selects.getFirst();
    }

    private Map<String, Object> explain(String sql, Object... args) {
        jdbc.execute("analyze table chat_messages");
        return jdbc.queryForList("explain " + sql, args).getFirst();
    }

    private void write(long conversationId, String prefix, int count) {
        // 한 문장씩 보내면 수천 번 왕복이라 여러 행 INSERT 로 묶는다
        for (int from = 0; from < count; from += 500) {
            int to = Math.min(count, from + 500);
            StringBuilder sql = new StringBuilder("insert into chat_messages (chat_session_id, role, content) values ");
            List<Object> args = new java.util.ArrayList<>();
            for (int i = from; i < to; i++) {
                sql.append(i == from ? "" : ",").append("(?, ?, ?)");
                args.add(conversationId);
                args.add(i % 2 == 0 ? "user" : "assistant");
                args.add(prefix + "-" + i);
            }
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    private static List<String> range(String prefix, int from, int to) {
        return java.util.stream.IntStream.range(from, to).mapToObj(i -> prefix + "-" + i).toList();
    }

    private static List<String> evens(String prefix, int from, int to) {
        return java.util.stream.IntStream.range(from, to).filter(i -> i % 2 == 0).mapToObj(i -> prefix + "-" + i).toList();
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
