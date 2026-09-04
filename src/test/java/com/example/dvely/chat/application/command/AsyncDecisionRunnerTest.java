package com.example.dvely.chat.application.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsyncDecisionRunnerTest {

    @Mock
    private DecisionAgentService decisionAgentService;

    @Mock
    private AgentMessageService agentMessageService;

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @InjectMocks
    private AsyncDecisionRunner asyncDecisionRunner;

    @Test
    void decidesFromUserIntentHistoryOnlyThenSubmitsTheDecidedPlan() {
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.ANTHROPIC, 7L);
        when(agentMessageService.getUserIntentHistory(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L)).thenReturn(plan);

        asyncDecisionRunner.decideAndSubmit("task-abc123", 2L, 21L, 7L, AiProvider.ANTHROPIC);

        // 계획 수립에는 사용자 발화 이력만 넘긴다 — 우리가 쓴 운영 안내가 섞이면 안 된다.
        verify(agentMessageService).getUserIntentHistory(21L);
        verify(decisionAgentService).decide(context, AiProvider.ANTHROPIC, 7L);
        verify(agentOrchestrator).submitDecided("task-abc123", plan, 2L, 21L);
        verify(agentOrchestrator, never()).markDecisionFailed(any(), any(), any());
    }

    @Test
    void passesTheRequestedProviderThroughToTheDecision() {
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.GLM, 7L);
        when(agentMessageService.getUserIntentHistory(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.GLM, 7L)).thenReturn(plan);

        asyncDecisionRunner.decideAndSubmit("task-glm", 2L, 21L, 7L, AiProvider.GLM);

        verify(decisionAgentService).decide(context, AiProvider.GLM, 7L);
        verify(agentOrchestrator).submitDecided("task-glm", plan, 2L, 21L);
    }

    @Test
    void closesTheTaskAsFailedWhenTheDecisionThrows() {
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        when(agentMessageService.getUserIntentHistory(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L))
                .thenThrow(new IllegalStateException("LLM 연결 실패"));

        asyncDecisionRunner.decideAndSubmit("task-abc123", 2L, 21L, 7L, AiProvider.ANTHROPIC);

        // PENDING 태스크가 계획 없이 고착되지 않도록 FAILED 로 닫고, 그 사유를 그대로 남긴다.
        verify(agentOrchestrator).markDecisionFailed("task-abc123", 21L, "LLM 연결 실패");
        verify(agentOrchestrator, never()).submitDecided(any(), any(), any(), any());
    }

    @Test
    void closesTheTaskAsFailedWhenSubmissionThrowsAfterASuccessfulDecision() {
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.ANTHROPIC, 7L);
        when(agentMessageService.getUserIntentHistory(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L)).thenReturn(plan);
        doThrow(new IllegalStateException("제출 실패"))
                .when(agentOrchestrator).submitDecided("task-abc123", plan, 2L, 21L);

        asyncDecisionRunner.decideAndSubmit("task-abc123", 2L, 21L, 7L, AiProvider.ANTHROPIC);

        // Decision 이 성공해도 제출이 깨지면 태스크는 여전히 PENDING — 반드시 FAILED 로 닫는다.
        verify(agentOrchestrator).markDecisionFailed("task-abc123", 21L, "제출 실패");
    }

    @Test
    void usesAFallbackMessageWhenTheFailureHasNoText() {
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        when(agentMessageService.getUserIntentHistory(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L))
                .thenThrow(new IllegalStateException());

        asyncDecisionRunner.decideAndSubmit("task-abc123", 2L, 21L, 7L, AiProvider.ANTHROPIC);

        verify(agentOrchestrator).markDecisionFailed("task-abc123", 21L, "알 수 없는 오류");
    }
}
