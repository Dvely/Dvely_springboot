package com.example.dvely.agent.application.orchestrator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.CodeAgentService;
import com.example.dvely.agent.application.service.DeployAgentService;
import com.example.dvely.agent.application.service.DomainBindAgentService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanExecutorTest {

    @Test
    void storesSuccessfulResultAsAssistantMessage() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                taskStore,
                messageService
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(step, AiProvider.OPENAI, 1L, 11L))
                .thenReturn(new CodeAgentService.CodeResult("preview", "수정 완료"));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(messageService).appendAssistant(21L, "수정 완료");
    }

    @Test
    void storesFailureAsAssistantMessage() {
        CodeAgentService codeService = mock(CodeAgentService.class);
        TaskStore taskStore = taskStore();
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentPlanExecutor executor = new AgentPlanExecutor(
                codeService,
                mock(DeployAgentService.class),
                mock(DomainBindAgentService.class),
                taskStore,
                messageService
        );
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "수정"));
        when(codeService.execute(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("빌드 실패"));

        executor.execute(
                new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L),
                "task-1",
                1L
        );

        verify(messageService).appendAssistant(21L, "작업 중 오류가 발생했습니다: 빌드 실패");
    }

    private TaskStore taskStore() {
        TaskStore taskStore = new TaskStore();
        taskStore.save(new AgentTask(
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
        ));
        return taskStore;
    }
}
