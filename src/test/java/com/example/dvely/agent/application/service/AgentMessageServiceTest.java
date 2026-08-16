package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 대화 이력에는 우리가 쓴 운영 안내가 어시스턴트 발화로 섞여 있다. 계획을 세우는 모델에 그걸
 * 그대로 넘기면 모델이 그 문장을 흉내 내다 JSON 을 내지 못하고, 파싱 실패로 CHAT 폴백이 된다 —
 * 오류는 뜨지 않고 코드만 한 줄도 안 바뀌는 조용한 실패다(dev 실측 4회).
 *
 * 그래서 계획용 입력과 대화용 입력을 나눈다.
 */
class AgentMessageServiceTest {

    private static final String PLAN_NOTICE = "작업 계획을 만들었습니다. 승인 후 실행합니다.\n- [69] CHANGE";
    private static final String START_NOTICE = "모든 승인이 완료되어 작업을 시작합니다.";

    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final AgentMessageService service = new AgentMessageService(repository);

    @Test
    void planningInputCarriesUserMessagesOnly() {
        givenConversation();

        List<LlmMessage> history = service.getUserIntentHistory(21L);

        assertThat(history).extracting(LlmMessage::content)
                .containsExactly("배경을 하늘색으로 바꿔줘", "글자를 바꿔줘");
        assertThat(history).extracting(LlmMessage::role).containsOnly("user");
    }

    @Test
    void planningInputNeverEchoesOurOwnNotices() {
        // 이 단언이 깨지면 모델이 흉내 낼 대상이 다시 생긴 것이다.
        givenConversation();

        assertThat(service.getUserIntentHistory(21L))
                .extracting(LlmMessage::content)
                .doesNotContain(PLAN_NOTICE, START_NOTICE);
    }

    @Test
    void conversationInputKeepsEverythingSoTheChatAgentCanFollowItself() {
        // 대화를 이어가는 쪽은 자기가 앞서 뭐라고 답했는지 알아야 한다. 여기까지 걸러내면
        // 말이 이어지지 않는다.
        givenConversation();

        assertThat(service.getConversationContext(21L))
                .extracting(LlmMessage::content)
                .containsExactly("배경을 하늘색으로 바꿔줘", PLAN_NOTICE, START_NOTICE, "글자를 바꿔줘");
    }

    private void givenConversation() {
        when(repository.findAllByConversationIdOrderByCreatedAtAsc(21L)).thenReturn(List.of(
                new ChatMessage(21L, ChatRole.USER, "배경을 하늘색으로 바꿔줘", 0),
                new ChatMessage(21L, ChatRole.ASSISTANT, PLAN_NOTICE, 0),
                new ChatMessage(21L, ChatRole.ASSISTANT, START_NOTICE, 0),
                new ChatMessage(21L, ChatRole.USER, "글자를 바꿔줘", 0)
        ));
    }
}
