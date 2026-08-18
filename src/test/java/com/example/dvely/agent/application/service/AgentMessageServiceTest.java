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
                .containsExactly(
                        "[참고: 이전 요청]\n배경을 하늘색으로 바꿔줘",
                        "[지금 처리할 요청]\n글자를 바꿔줘"
                );
        assertThat(history).extracting(LlmMessage::role).containsOnly("user");
    }

    @Test
    void onlyTheNewestRequestIsMarkedAsTheOneToPlan() {
        // 어시스턴트 발화를 걷어낸 대가로 "이 요청은 이미 처리됐다"는 신호까지 사라진다. 표시가
        // 없으면 모델에게는 지난 요청도 방금 들어온 요청과 똑같이 미처리로 보이고, 실제로 운영에서
        // 이미 처리하고 거절까지 끝난 옛 요청의 계획을 다시 냈다(2026-08-18).
        givenConversation();

        List<LlmMessage> history = service.getUserIntentHistory(21L);

        assertThat(history).hasSize(2);
        assertThat(history.getLast().content()).startsWith("[지금 처리할 요청]");
        assertThat(history.getFirst().content()).doesNotContain("[지금 처리할 요청]");
    }

    @Test
    void aSingleMessageConversationMarksThatMessageAsCurrent() {
        when(repository.findAllByConversationIdOrderByCreatedAtAsc(21L)).thenReturn(List.of(
                new ChatMessage(21L, ChatRole.USER, "포트폴리오 만들어줘", 0)
        ));

        assertThat(service.getUserIntentHistory(21L))
                .extracting(LlmMessage::content)
                .containsExactly("[지금 처리할 요청]\n포트폴리오 만들어줘");
    }

    @Test
    void anEmptyConversationYieldsNoPlanningInput() {
        when(repository.findAllByConversationIdOrderByCreatedAtAsc(21L)).thenReturn(List.of());

        assertThat(service.getUserIntentHistory(21L)).isEmpty();
    }

    @Test
    void planningInputNeverEchoesOurOwnNotices() {
        // 이 단언이 깨지면 모델이 흉내 낼 대상이 다시 생긴 것이다.
        givenConversation();

        assertThat(service.getUserIntentHistory(21L))
                .extracting(LlmMessage::content)
                .noneMatch(content -> content.contains(PLAN_NOTICE) || content.contains(START_NOTICE));
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
