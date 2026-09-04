package com.example.dvely.chat.application.command;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 메시지 발화의 Decision(LLM 호출→AgentPlan)을 요청 스레드 밖에서 돌리는 비동기 후반부.
 *
 * <p>{@code ChatCommandService#sendMessage} 는 사용자 메시지를 저장하고 {@link
 * AgentOrchestrator#createPending} 로 taskId 만 즉시 받아 응답한다(그래야 FE 가 Decision 이 끝나기를
 * 기다리지 않고 바로 SSE 를 연다). 그 뒤 트랜잭션이 커밋되면 여기 {@link #decideAndSubmit} 가
 * 백그라운드에서 Decision → {@link AgentOrchestrator#submitDecided} 로 계획을 확정한다.</p>
 *
 * <p><b>불변식:</b> createPending 이 연 PENDING 태스크는 반드시 여기서 확정(QUEUED/승인 대기)되거나
 * {@link AgentOrchestrator#markDecisionFailed} 로 FAILED 로 닫힌다 — 계획 없이 PENDING 으로 고착되는
 * 경우가 없어야 한다. 그래서 try 는 Decision 과 submitDecided 를 모두 감싸고, 어떤 RuntimeException
 * 이 나도 catch 에서 태스크를 FAILED 로 전이시킨다. Decision 호출은 #238 의 read timeout(180s)으로
 * 유계라 무한 대기로 스레드가 묶이지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncDecisionRunner {

    private final DecisionAgentService decisionAgentService;
    private final AgentMessageService agentMessageService;
    private final AgentOrchestrator agentOrchestrator;

    /**
     * @param taskId         {@link AgentOrchestrator#createPending} 가 발급한 PENDING 태스크 id
     * @param userId         소유자
     * @param conversationId 발화가 속한 대화
     * @param projectId      Decision 에 넘길 프로젝트 컨텍스트(대화의 projectId 그대로 — sendMessage 가
     *                       이미 로드해 둔 값)
     * @param provider       사용할 AI 제공자(요청 지정값 또는 기본값, sendMessage 에서 해석 완료)
     */
    @Async("agentDecisionExecutor")
    public void decideAndSubmit(String taskId,
                                Long userId,
                                Long conversationId,
                                Long projectId,
                                AiProvider provider) {
        try {
            // 계획 수립에는 사용자 발화만 넘긴다. 우리가 쓴 운영 안내가 섞이면 모델이 그 문장을
            // 흉내 내다 JSON 을 내지 못한다 — AgentMessageService#getUserIntentHistory 참고.
            List<LlmMessage> history = agentMessageService.getUserIntentHistory(conversationId);
            AgentPlan plan = decisionAgentService.decide(history, provider, projectId);
            agentOrchestrator.submitDecided(taskId, plan, userId, conversationId);
        } catch (RuntimeException exception) {
            log.warn("[AsyncDecisionRunner] Decision 실패로 태스크를 FAILED 로 닫습니다. taskId={}", taskId, exception);
            try {
                agentOrchestrator.markDecisionFailed(taskId, conversationId, safeMessage(exception));
            } catch (RuntimeException closeFailure) {
                // 여기까지 실패하면 태스크가 PENDING 으로 남을 수 있다 — 이 로그가 유일한 단서다.
                log.error("[AsyncDecisionRunner] 태스크 FAILED 전이마저 실패했습니다. taskId={}", taskId, closeFailure);
            }
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "알 수 없는 오류"
                : exception.getMessage();
    }
}
