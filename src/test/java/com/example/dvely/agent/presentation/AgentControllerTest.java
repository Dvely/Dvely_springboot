package com.example.dvely.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.example.dvely.common.exception.NotFoundException;
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
        assertThatThrownBy(() -> controller.getTaskStatus(2L, "task-1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignUserCannotSubmitTaskInput() {
        assertThatThrownBy(() -> controller.submitInput(2L, "task-1", new TaskInputRequest("secret")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignUserCannotCancelTask() {
        assertThatThrownBy(() -> controller.cancelTask(2L, "task-1"))
                .isInstanceOf(NotFoundException.class);
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
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(inputWaitStore).supply("task-1", 1L, "my-domain");
    }

    @Test
    void ownerCannotSubmitInputWhenTaskIsNotWaiting() {
        when(taskStore.getOwned("task-1", 1L)).thenReturn(new AgentTask(
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

        assertThatThrownBy(() -> controller.submitInput(
                1L,
                "task-1",
                new TaskInputRequest("secret")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("입력을 기다리는");
    }
}
