package com.example.dvely.agent.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskStoreTest {

    private final TaskStore taskStore = new TaskStore();

    @Test
    void exposesTaskOnlyToOwner() {
        taskStore.save(task(TaskStatus.RUNNING));

        assertThat(taskStore.getOwned("task-1", 1L)).isNotNull();
        assertThat(taskStore.getOwned("task-1", 2L)).isNull();
    }

    @Test
    void foreignUserCannotCancelTask() {
        taskStore.save(task(TaskStatus.RUNNING));

        assertThat(taskStore.cancel("task-1", 2L)).isFalse();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void cancelledTaskCannotReturnToRunningOrDone() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.cancel("task-1", 1L)).isTrue();
        taskStore.markRunning("task-1");
        taskStore.markDone("task-1", "preview", "done");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    private AgentTask task(TaskStatus status) {
        return new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                status,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }
}
