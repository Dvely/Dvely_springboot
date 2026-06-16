package com.example.dvely.agent.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunEventRepository;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskStoreTest {

    private final Map<String, AgentRunEntity> runs = new HashMap<>();
    private SpringDataAgentRunRepository runRepository;
    private SpringDataAgentRunEventRepository eventRepository;
    private TaskStore taskStore;

    @BeforeEach
    void setUp() {
        runRepository = mock(SpringDataAgentRunRepository.class);
        eventRepository = mock(SpringDataAgentRunEventRepository.class);
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            runs.put(run.getTaskId(), run);
            return run;
        });
        when(runRepository.findById(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(runs.get(invocation.getArgument(0))));
        when(runRepository.findByTaskIdAndOwnerUserId(any(String.class), any(Long.class)))
                .thenAnswer(invocation -> {
                    AgentRunEntity run = runs.get(invocation.getArgument(0));
                    Long ownerUserId = invocation.getArgument(1);
                    return run != null && run.getOwnerUserId().equals(ownerUserId)
                            ? Optional.of(run)
                            : Optional.empty();
                });
        taskStore = new TaskStore(runRepository, eventRepository, new ObjectMapper());
    }

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
        taskStore.markDone("task-1", "preview", "done");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void inputSurvivesWaitAndQueuesSameTask() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, "my-domain")).isTrue();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(taskStore.consumeInput("task-1")).contains("my-domain");
    }

    @Test
    void repeatedInputSupplyIsRejectedAfterTaskIsQueued() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, "first")).isTrue();
        assertThat(taskStore.supplyInput("task-1", 1L, "second")).isFalse();
        assertThat(taskStore.consumeInput("task-1")).contains("first");
    }

    @Test
    void blankInputSupplyIsRejectedAndTaskStaysWaiting() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, " ")).isFalse();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.WAITING_INPUT);
        assertThat(taskStore.consumeInput("task-1")).isEmpty();
    }

    @Test
    void persistedPlanCanBeReadByNewStoreInstance() {
        taskStore.save(task(TaskStatus.PENDING));
        AgentPlan plan = new AgentPlan(
                java.util.List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정"))),
                "reason",
                AiProvider.OPENAI,
                11L
        );
        taskStore.savePlan("task-1", plan);

        TaskStore restartedStore = new TaskStore(runRepository, eventRepository, new ObjectMapper());

        assertThat(restartedStore.getPlan("task-1")).isEqualTo(plan);
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
