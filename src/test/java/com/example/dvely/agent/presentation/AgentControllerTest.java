package com.example.dvely.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.infrastructure.docker.UserContainerRegistry;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.presentation.dto.TaskInputRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentControllerTest {

    private TaskStore taskStore;
    private InputWaitStore inputWaitStore;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        taskStore = new TaskStore();
        inputWaitStore = new InputWaitStore();
        controller = new AgentController(
                mock(DecisionAgentService.class),
                mock(AgentOrchestrator.class),
                taskStore,
                mock(UserContainerRegistry.class),
                inputWaitStore
        );
        taskStore.save(new AgentTask(
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
        ));
    }

    @Test
    void foreignUserCannotReadTaskStatus() {
        assertThat(controller.getTaskStatus(2L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void foreignUserCannotSubmitTaskInput() {
        inputWaitStore.register("task-1");

        assertThat(controller.submitInput(2L, "task-1", new TaskInputRequest("secret")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void foreignUserCannotCancelTask() {
        assertThat(controller.cancelTask(2L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(taskStore.getOwned("task-1", 1L).status())
                .isEqualTo(TaskStatus.WAITING_INPUT);
    }

    @Test
    void ownerCanCancelTask() {
        inputWaitStore.register("task-1");

        assertThat(controller.cancelTask(1L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(taskStore.getOwned("task-1", 1L).status())
                .isEqualTo(TaskStatus.CANCELLED);
    }
}
