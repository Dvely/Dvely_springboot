package com.example.dvely.agent.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AgentRunWorkerTest {

    private static final long BACKOFF_MS = 5000L;

    @Test
    void recoversLeasesAndDispatchesClaimedTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        AgentExecutionRegistry registry = new AgentExecutionRegistry();
        AgentRunWorker worker = new AgentRunWorker(taskStore, executor, registry, freeExecutor(), BACKOFF_MS);
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L);
        AgentTask task = agentTask();
        when(taskStore.claimRunnableTasks(anyString(), eq(2))).thenReturn(List.of("task-1"));
        when(taskStore.get("task-1")).thenReturn(task);
        when(taskStore.getPlan("task-1")).thenReturn(plan);

        worker.dispatchQueuedRuns();

        verify(taskStore).recoverExpiredLeases();
        verify(executor).execute(plan, "task-1", 1L);
    }

    // ── ADR-Y4 (#55): register-before-submit ────────────────────────────────────────────────

    @Test
    void registersTaskInExecutionRegistryBeforeSubmittingToTheExecutor() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        AgentRunWorker worker = new AgentRunWorker(taskStore, executor, registry, freeExecutor(), BACKOFF_MS);
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L);
        AgentTask task = agentTask();
        when(taskStore.claimRunnableTasks(anyString(), eq(2))).thenReturn(List.of("task-1"));
        when(taskStore.get("task-1")).thenReturn(task);
        when(taskStore.getPlan("task-1")).thenReturn(plan);

        worker.dispatchQueuedRuns();

        // Registration must happen before the (synchronous, from this thread's perspective)
        // executor.execute() proxy call returns — verifying both calls happened, in this test's
        // single-threaded setup, is enough to prove register() is not deferred into execute()'s
        // own body (AgentExecutionRegistry javadoc's "must not happen inside execute() itself").
        verify(registry).register("task-1");
        verify(executor).execute(plan, "task-1", 1L);
    }

    // ── ADR-Y3 (#55): per-task try/catch — a rejected task must not abort the batch ────────────

    @Test
    void executorRejectionReleasesClaimUnregistersAndContinuesDispatchingTheRestOfTheBatch() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        AgentRunWorker worker = new AgentRunWorker(taskStore, executor, registry, freeExecutor(), BACKOFF_MS);
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L);
        AgentTask rejectedTask = agentTask("task-1");
        AgentTask okTask = agentTask("task-2");
        when(taskStore.claimRunnableTasks(anyString(), eq(2))).thenReturn(List.of("task-1", "task-2"));
        when(taskStore.get("task-1")).thenReturn(rejectedTask);
        when(taskStore.get("task-2")).thenReturn(okTask);
        when(taskStore.getPlan("task-1")).thenReturn(plan);
        when(taskStore.getPlan("task-2")).thenReturn(plan);
        doThrow(new TaskRejectedException("pool saturated"))
                .when(executor).execute(plan, "task-1", 1L);
        when(taskStore.releaseClaim(eq("task-1"), anyString(), eq(BACKOFF_MS))).thenReturn(true);

        worker.dispatchQueuedRuns();

        // F4 regression guard: task-1's rejection must not prevent task-2 (same batch, dispatched
        // after task-1 in claim order) from being submitted.
        verify(executor).execute(plan, "task-2", 1L);
        verify(registry).unregister("task-1");
        verify(taskStore).releaseClaim("task-1", workerIdOf(worker), BACKOFF_MS);
        // task-2 succeeded — never released/unregistered.
        verify(taskStore, never()).releaseClaim(eq("task-2"), anyString(), anyLong());
        verify(registry, never()).unregister("task-2");
    }

    @Test
    void heartbeatSkipsTheRenewalQueryWhenTheRegistryIsEmpty() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentExecutionRegistry registry = new AgentExecutionRegistry();
        AgentRunWorker worker = new AgentRunWorker(taskStore, mock(AgentPlanExecutor.class), registry,
                freeExecutor(), BACKOFF_MS);

        worker.renewLeases();

        verify(taskStore, never()).renewWorkerLeases(anyString(), org.mockito.ArgumentMatchers.anySet());
    }

    @Test
    void heartbeatRenewsExactlyTheRegisteredTaskIds() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentExecutionRegistry registry = new AgentExecutionRegistry();
        registry.register("task-1");
        registry.register("task-2");
        AgentRunWorker worker = new AgentRunWorker(taskStore, mock(AgentPlanExecutor.class), registry,
                freeExecutor(), BACKOFF_MS);

        worker.renewLeases();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(taskStore, times(1)).renewWorkerLeases(anyString(), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("task-1", "task-2");
    }

    // ── ADR-Y3 SHOULD: capacity-aware claim ─────────────────────────────────────────────────

    @Test
    void skipsClaimEntirelyWhenTheExecutorHasNoFreeCapacity() throws InterruptedException {
        TaskStore taskStore = mock(TaskStore.class);
        ThreadPoolTaskExecutor saturated = new ThreadPoolTaskExecutor();
        saturated.setCorePoolSize(1);
        saturated.setMaxPoolSize(1);
        saturated.setQueueCapacity(0);
        saturated.initialize();
        // Occupy the only thread with a task that blocks until released, so pool+queue are both
        // full for the duration of this test — a real saturation state, not a mocked stand-in.
        // `started` removes the race between this thread submitting the task and the worker
        // thread actually beginning to run it (getActiveCount() would otherwise be readable as 0
        // for a brief window right after submission, flaking the free-slots estimate below).
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        saturated.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            started.await(2, java.util.concurrent.TimeUnit.SECONDS);
            AgentRunWorker worker = new AgentRunWorker(taskStore, mock(AgentPlanExecutor.class),
                    new AgentExecutionRegistry(), saturated, BACKOFF_MS);

            worker.dispatchQueuedRuns();

            verify(taskStore).recoverExpiredLeases();
            verify(taskStore, never()).claimRunnableTasks(anyString(), org.mockito.ArgumentMatchers.anyInt());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    private String workerIdOf(AgentRunWorker worker) {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }

    private ThreadPoolTaskExecutor freeExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(2);
        threadPoolTaskExecutor.setMaxPoolSize(5);
        threadPoolTaskExecutor.setQueueCapacity(10);
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }

    private AgentTask agentTask() {
        return agentTask("task-1");
    }

    private AgentTask agentTask(String taskId) {
        return new AgentTask(
                taskId,
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
    }
}
