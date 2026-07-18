package com.example.dvely.agent.application.orchestrator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.change.application.service.ChangeService;
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
        when(codeService.execute(step, AiProvider.OPENAI, 1L, 11L, "task-1"))
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

    @Test
    void dispatchesChatStepToChatAgentServiceAndStoresAnswerAsSummary() {
        ChatAgentService chatService = mock(ChatAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = executor(mock(CodeAgentService.class), chatService, taskStore, messageService);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "휴지통 정책이 뭐야?"));
        when(chatService.execute(step, AiProvider.ANTHROPIC, "task-1"))
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
        when(codeService.execute(any(), any(), any(), any(), any()))
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
                mock(ChangeService.class)
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
                mock(ChangeService.class)
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
                mock(ChangeService.class)
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
