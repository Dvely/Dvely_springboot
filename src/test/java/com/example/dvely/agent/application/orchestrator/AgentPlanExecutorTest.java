package com.example.dvely.agent.application.orchestrator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.BuildFailureRecoveryService;
import com.example.dvely.agent.application.service.ChatAgentService;
import com.example.dvely.agent.application.service.CodeAgentService;
import com.example.dvely.agent.application.service.DeployAgentService;
import com.example.dvely.agent.application.service.DomainBindAgentService;
import com.example.dvely.agent.application.service.InfraOpsAgentService;
import com.example.dvely.agent.application.service.RepositoryBindingGate;
import com.example.dvely.agent.application.service.ResultApprovalGate;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.infrastructure.worker.AgentExecutionRegistry;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.common.exception.LlmProviderException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanExecutorTest {

    @Test
    void storesSuccessfulResultAndCompletedStep() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = executor(codeService, taskStore, messageService);
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(taskStore).markStepCompleted("task-1", 1);
        verify(taskStore).markDone("task-1", "preview", "수정 완료");
        verify(messageService).appendAssistant(21L, "수정 완료");
    }

    // ── ADR-Y4 (#55): AgentExecutionRegistry unregister-in-finally wiring ──────────────────────

    @Test
    void unregistersFromExecutionRegistryAfterSuccessfulCompletion() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                mock(AgentMessageService.class),
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                registry
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));

        executor.execute(new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L), "task-1", 1L);

        verify(registry).unregister("task-1");
    }

    @Test
    void unregistersFromExecutionRegistryEvenWhenTheStepThrows() {
        // The finally-wrapping (ADR-Y4) must unregister on every exit path, not just the happy
        // path — a leaked registry entry after a genuinely failed/finished execution would let a
        // *different*, unrelated future claim of the same taskId be silently heartbeat-protected
        // by this stale entry.
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                mock(AgentMessageService.class),
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                registry
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("외부 API 실패"));

        executor.execute(new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L), "task-1", 1L);

        verify(taskStore).markFailed("task-1", "외부 API 실패");
        verify(registry).unregister("task-1");
    }

    // ── Track Z (#56): result-approval gate wiring ──────────────────────────────────────────

    @Test
    void stopsAfterLastCodeStepWithoutMarkingDoneWhenResultApprovalGateFires() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalGate gate = mock(ResultApprovalGate.class);
        ChangeService changeService = mock(ChangeService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                messageService,
                mock(BuildFailureRecoveryService.class),
                changeService,
                gate,
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        AgentPlan plan = new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));
        // The gate itself is responsible for markStepCompleted when it fires (see
        // ResultApprovalGate javadoc) — this test only asserts the executor's side: record the
        // diff, delegate to the gate, then stop without ever calling markDone/removePlan.
        when(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).thenReturn(true);

        executor.execute(plan, "task-1", 1L);

        verify(changeService).record("task-1", "수정 완료");
        verify(gate).requestIfRequired(plan, 0, "task-1", 1L, 11L);
        verify(taskStore, org.mockito.Mockito.never()).markStepCompleted(org.mockito.ArgumentMatchers.eq("task-1"), org.mockito.ArgumentMatchers.anyInt());
        verify(taskStore, org.mockito.Mockito.never()).markDone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(taskStore, org.mockito.Mockito.never()).removePlan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stopsAfterLastCodeStepWithoutMarkingDoneWhenRepositoryBindingGateFires() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        ResultApprovalGate resultGate = mock(ResultApprovalGate.class);
        RepositoryBindingGate bindingGate = mock(RepositoryBindingGate.class);
        ChangeService changeService = mock(ChangeService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                mock(AgentMessageService.class),
                mock(BuildFailureRecoveryService.class),
                changeService,
                resultGate,
                bindingGate,
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        AgentPlan plan = new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));
        // NOT_BOUND project: the result gate declines, the binding gate takes over. Same contract
        // as the result gate — it owns markStepCompleted, so the executor must stop without
        // markDone/removePlan.
        when(resultGate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).thenReturn(false);
        when(bindingGate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).thenReturn(true);

        executor.execute(plan, "task-1", 1L);

        verify(changeService).record("task-1", "수정 완료");
        verify(bindingGate).requestIfRequired(plan, 0, "task-1", 1L, 11L);
        verify(taskStore, org.mockito.Mockito.never()).markStepCompleted(org.mockito.ArgumentMatchers.eq("task-1"), org.mockito.ArgumentMatchers.anyInt());
        verify(taskStore, org.mockito.Mockito.never()).markDone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(taskStore, org.mockito.Mockito.never()).removePlan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsRepositoryBindingGateEntirelyWhenTheResultGateAlreadyFired() {
        // The two gates branch on the same repositoryBindingStatus, so exactly one may fire. If
        // the result gate parked the task, evaluating the binding gate afterwards would open a
        // second approval on a task that is already waiting on the first one.
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        ResultApprovalGate resultGate = mock(ResultApprovalGate.class);
        RepositoryBindingGate bindingGate = mock(RepositoryBindingGate.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                mock(AgentMessageService.class),
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                resultGate,
                bindingGate,
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        AgentPlan plan = new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));
        when(resultGate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).thenReturn(true);

        executor.execute(plan, "task-1", 1L);

        org.mockito.Mockito.verifyNoInteractions(bindingGate);
    }

    @Test
    void continuesNormallyWhenResultApprovalGateDoesNotFire() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalGate gate = mock(ResultApprovalGate.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                messageService,
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                gate,
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        AgentPlan plan = new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));
        when(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).thenReturn(false);

        executor.execute(plan, "task-1", 1L);

        verify(taskStore).markStepCompleted("task-1", 1);
        verify(taskStore).markDone("task-1", "preview", "수정 완료");
    }

    @Test
    void dispatchesChatStepToChatAgentServiceAndStoresAnswerAsSummary() {
        ChatAgentService chatService = mock(ChatAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = executor(mock(CodeAgentService.class), chatService, taskStore, messageService);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "휴지통 정책이 뭐야?"));
        when(chatService.execute(eq(step), eq(AiProvider.ANTHROPIC), eq("task-1"), any()))
                .thenReturn(new CodeAgentService.CodeResult(null, "휴지통 보관 기간은 7일입니다."));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.ANTHROPIC, 11L),
                "task-1",
                1L
        );

        verify(taskStore).markStepCompleted("task-1", 1);
        verify(taskStore).markDone("task-1", null, "휴지통 보관 기간은 7일입니다.");
        verify(messageService).appendAssistant(21L, "휴지통 보관 기간은 7일입니다.");
    }

    @Test
    void storesFailureAsAssistantMessage() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = executor(codeService, taskStore, messageService);
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("외부 API 실패"));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(taskStore).markFailed("task-1", "외부 API 실패");
        verify(messageService).appendAssistant(21L, "작업 중 오류가 발생했습니다: 외부 API 실패");
    }

    @Test
    void waitingInputPersistsQuestionWithoutFailingTask() {
        DomainBindAgentService domainService = mock(DomainBindAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                mock(CodeAgentService.class),
                mock(DeployAgentService.class),
                domainService,
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                messageService,
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );

        AgentStep step = new AgentStep(AgentType.DOMAIN_BIND, Map.of());
        when(domainService.execute(step, 1L, "task-1", 11L))
                .thenThrow(new AgentInputRequiredException("도메인을 입력해주세요."));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(taskStore).markWaitingInput("task-1", "도메인을 입력해주세요.");
        verify(messageService).appendAssistant(21L, "도메인을 입력해주세요.");
    }

    @Test
    void dispatchesInfraOperateStepToInfraOpsAgentService() {
        InfraOpsAgentService infraOpsAgentService = mock(InfraOpsAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                mock(CodeAgentService.class),
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                infraOpsAgentService,
                taskStore,
                messageService,
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));
        when(infraOpsAgentService.execute(step, 1L, "task-1", 11L))
                .thenReturn(new CodeAgentService.CodeResult(null, "서버/서비스 상태\n- 배포: 배포 이력 없음"));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(taskStore).markDone("task-1", null, "서버/서비스 상태\n- 배포: 배포 이력 없음");
        verify(messageService).appendAssistant(21L, "서버/서비스 상태\n- 배포: 배포 이력 없음");
    }

    @Test
    void reportsAnAiProviderFailureWithTheProvidersOwnMessageAndWithoutBuildRecovery() {
        // A provider-side failure (no credit, rejected key, outage) is not a build failure: routing
        // it through BuildFailureRecoveryService would tell the user their project failed to build
        // and then spend the retry budget on a call that cannot start succeeding in between.
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        BuildFailureRecoveryService recoveryService = mock(BuildFailureRecoveryService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                mock(ChatAgentService.class),
                mock(InfraOpsAgentService.class),
                taskStore,
                messageService,
                recoveryService,
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        LlmProviderException failure = new LlmProviderException(
                "OpenAI", LlmProviderException.Reason.QUOTA_EXCEEDED, null
        );
        when(codeService.execute(eq(step), eq(AiProvider.OPENAI), eq(1L), eq(11L), eq("task-1"), any())).thenThrow(failure);

        executor.execute(new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L), "task-1", 1L);

        verify(taskStore).markFailed("task-1", failure.getMessage());
        // Posted verbatim — the message already tells the user to switch providers or check
        // billing, and a "작업 중 오류가 발생했습니다: " prefix would bury that.
        verify(messageService).appendAssistant(21L, failure.getMessage());
        verify(recoveryService, never()).handle(any(), any());
        verify(taskStore, never()).markDone(any(), any(), any());
    }

    private AgentPlanExecutor executor(CodeAgentService codeService,
                                       TaskStore taskStore,
                                       AgentMessageService messageService) {
        return executor(codeService, mock(ChatAgentService.class), taskStore, messageService);
    }

    private AgentPlanExecutor executor(CodeAgentService codeService,
                                       ChatAgentService chatService,
                                       TaskStore taskStore,
                                       AgentMessageService messageService) {
        return new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                chatService,
                mock(InfraOpsAgentService.class),
                taskStore,
                messageService,
                mock(BuildFailureRecoveryService.class),
                mock(ChangeService.class),
                mock(ResultApprovalGate.class),
                mock(RepositoryBindingGate.class),
                mock(AgentExecutionRegistry.class)
        );
    }

    private TaskStore taskStore() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentTask task = new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.RUNNING,
                null,
                null,
                null,
                null,
                Instant.now()
        );
        when(taskStore.get("task-1")).thenReturn(task);
        when(taskStore.getCurrentStep("task-1")).thenReturn(0);
        return taskStore;
    }
}
