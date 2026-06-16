package com.example.dvely.agent.infrastructure.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentPlanExecutor;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunWorkerTest {

    @Test
    void recoversLeasesAndDispatchesClaimedTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        AgentRunWorker worker = new AgentRunWorker(taskStore, executor);
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L);
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
        when(taskStore.claimRunnableTasks(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(List.of("task-1"));
        when(taskStore.get("task-1")).thenReturn(task);
        when(taskStore.getPlan("task-1")).thenReturn(plan);

        worker.dispatchQueuedRuns();

        verify(taskStore).recoverExpiredLeases();
        verify(executor).execute(plan, "task-1", 1L);
    }
}
