package com.example.dvely.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentEventStreamService;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.presentation.dto.TaskInputRequest;
import com.example.dvely.preview.application.service.PreviewSessionService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentControllerTest {

    private TaskStore taskStore;
    private InputWaitStore inputWaitStore;
    private AgentOrchestrator agentOrchestrator;
    private AgentController controller;
    private AgentTask task;

    @BeforeEach
    void setUp() {
        taskStore = mock(TaskStore.class);
        inputWaitStore = mock(InputWaitStore.class);
        agentOrchestrator = mock(AgentOrchestrator.class);
        controller = new AgentController(
                mock(DecisionAgentService.class),
                agentOrchestrator,
                taskStore,
                inputWaitStore,
                mock(PreviewSessionService.class),
                mock(AgentEventStreamService.class)
        );
        task = new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.WAITING_INPUT,
                null,
                null,
                null,
                "question",
                Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(task);
    }

    @Test
    void foreignUserCannotReadTaskStatus() {
        assertThat(controller.getTaskStatus(2L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void foreignUserCannotSubmitTaskInput() {
        assertThat(controller.submitInput(2L, "task-1", new TaskInputRequest("secret")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void foreignUserCannotCancelTask() {
        assertThat(controller.cancelTask(2L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerCanCancelTask() {
        when(agentOrchestrator.cancel("task-1", 1L)).thenReturn(true);

        assertThat(controller.cancelTask(1L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(agentOrchestrator).cancel("task-1", 1L);
    }

    @Test
    void ownerInputQueuesPersistentTask() {
        when(inputWaitStore.supply("task-1", 1L, "my-domain")).thenReturn(true);

        assertThat(controller.submitInput(1L, "task-1", new TaskInputRequest("my-domain")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(inputWaitStore).supply("task-1", 1L, "my-domain");
    }
}
