package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.value.ChatMessageKind;
import com.example.dvely.chat.domain.value.ChatRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentMessageService {

    /**
     * 사용자 발화만 넘기면 어느 것이 지금 처리할 요청인지가 사라진다. 어시스턴트 발화를 걷어낸
     * 대가로 "이 요청은 이미 처리됐다"는 신호까지 함께 사라지기 때문에, 모델에게는 지난 요청도
     * 방금 들어온 요청과 똑같이 미처리로 보인다. 그래서 마지막 발화에만 표시를 붙인다.
     */
    private static final String CURRENT_REQUEST_LABEL = "[지금 처리할 요청]\n";
    private static final String PAST_REQUEST_LABEL = "[참고: 이전 요청]\n";

    private final ChatMessageRepository chatMessageRepository;

    /** 종류를 붙이지 않는 줄(순수 대화 답변 등). */
    @Transactional
    public void appendAssistant(Long conversationId, String content) {
        appendAssistant(conversationId, content, null);
    }

    /** 태스크와 이어지지 않는 줄. */
    @Transactional
    public void appendAssistant(Long conversationId, String content, ChatMessageKind kind) {
        appendAssistant(conversationId, content, kind, null);
    }

    /**
     * 종류를 붙이고 태스크에 이어 남긴다.
     *
     * <p>종류는 화면이 본문 문자열로 의미를 추론하지 않게 한다 — 그 추론은 이미 한 번 사고를
     * 냈다(존재하지 않는 승인 버튼). {@link ChatMessageKind} 참고.</p>
     *
     * <p>taskId 는 "이 줄이 어느 작업의 것인가" 를 남긴다. 없으면 결과 줄에서 그 작업의 diff 로
     * 넘어가거나 실패 줄에서 그 작업만 재시도하는 것이 불가능하다 — 화면은 "가장 마지막 것"
     * 같은 휴리스틱으로 짐작할 수밖에 없다.</p>
     */
    @Transactional
    public void appendAssistant(Long conversationId, String content, ChatMessageKind kind, String taskId) {
        if (conversationId == null || content == null || content.isBlank()) {
            return;
        }
        chatMessageRepository.save(new ChatMessage(
                conversationId,
                ChatRole.ASSISTANT,
                content.trim(),
                0,
                kind,
                taskId
        ));
    }

    /**
     * 대화 전체. 대화를 이어가는 ChatAgentService 가 쓴다 — 자기가 앞서 뭐라고 답했는지 알아야
     * 말이 이어진다.
     *
     * 계획을 세우는 쪽(DecisionAgentService)에는 이걸 주면 안 된다. {@link #getUserIntentHistory}
     * 를 쓸 것.
     */
    @Transactional(readOnly = true)
    public List<LlmMessage> getConversationContext(Long conversationId) {
        // 전량을 싣던 자리다. 오래 쓴 대화일수록 모든 요청이 비싸졌고 언젠가는 컨텍스트 상한에
        // 닿았다 — 무엇을 잃는지는 ConversationWindow 참고.
        return ConversationWindow.apply(
                chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)
                        .stream()
                        .map(message -> new LlmMessage(
                                message.getRole().toStorage(),
                                message.getContent()
                        ))
                        .toList());
    }

    /**
     * 사용자 발화만. 계획을 세우는 쪽에 준다.
     *
     * 이 대화에는 우리가 쓴 운영 안내가 어시스턴트 발화로 섞여 있다 — "작업 계획을 만들었습니다.
     * 승인 후 실행합니다.", "모든 승인이 완료되어 작업을 시작합니다." 같은 것들이다. JSON 계획을
     * 내야 하는 모델에 이걸 그대로 넘기면 모델이 그 문장을 흉내 낸다. 실제로 그 raw 응답 그대로
     * 파싱에 실패해 CHAT 으로 폴백했다(dev 실측: 2026-08-14 · 08-15 · 08-16 동일 문구로 4회).
     *
     * 증상이 조용한 것이 고약하다. 오류가 뜨지 않고 에이전트가 "승인이라고 말씀해 주세요" 같은
     * 자작 문구를 내므로, 사용자는 진행 중이라 믿고 기다리는데 코드는 한 줄도 바뀌지 않는다.
     *
     * 사용자 발화만 넘기면 흉내 낼 대상 자체가 사라진다. 잃는 것은 "에이전트가 직전에 무엇을
     * 만들었는가"인데, 그 정보는 코드 작업 시점에 컨테이너의 실제 파일에서 다시 확인되므로
     * 계획 단계에서 필요하지 않다.
     *
     * 다만 하나는 되돌려줘야 한다 — <b>어느 것이 지금 처리할 요청인가</b>. 어시스턴트 발화에는
     * "작업을 시작합니다" 같은 처리 흔적이 섞여 있어서, 그걸 걷어내면 지난 요청도 방금 들어온
     * 요청과 똑같이 미처리로 보인다. 실제로 운영에서 모델이 이미 처리하고 거절까지 끝난 옛
     * 요청을 다시 계획했다(2026-08-18: "지금 프리뷰 서버 상태 확인해줘"에 대해 직전 요청이던
     * "제목을 '거절 테스트'로 바꿔줘"의 계획을 냈다). 그래서 마지막 발화에만 표시를 붙인다.
     */
    @Transactional(readOnly = true)
    public List<LlmMessage> getUserIntentHistory(Long conversationId) {
        List<ChatMessage> userMessages = chatMessageRepository
                .findAllByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .filter(message -> message.getRole() == ChatRole.USER)
                .toList();
        if (userMessages.isEmpty()) {
            return List.of();
        }

        int last = userMessages.size() - 1;
        // 표시를 먼저 붙이고 그다음에 자른다. 순서가 반대면 창 안의 마지막 턴에 [지금 처리할
        // 요청] 이 붙어, 이미 처리된 옛 요청이 새 요청으로 둔갑한다.
        return ConversationWindow.apply(java.util.stream.IntStream.range(0, userMessages.size())
                .mapToObj(index -> new LlmMessage(
                        ChatRole.USER.toStorage(),
                        (index == last ? CURRENT_REQUEST_LABEL : PAST_REQUEST_LABEL)
                                + userMessages.get(index).getContent()
                ))
                .toList());
    }
}
