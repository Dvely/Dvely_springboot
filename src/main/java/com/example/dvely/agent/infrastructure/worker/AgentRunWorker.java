package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.orchestrator.AgentPlanExecutor;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Polls for runnable Agent tasks and hands each to {@link AgentPlanExecutor} for async execution.
 * Design ADR-Y3/Y4 (#55): every per-task step below is deliberately isolated — its own try/catch,
 * its own {@link AgentExecutionRegistry} bookkeeping — so one executor rejection degrades to
 * "that task goes back to QUEUED", never to "the rest of this poll's claimed batch silently never
 * runs" (the D2/G1 bug this class used to have: an uncaught {@code TaskRejectedException} aborted
 * {@code dispatchQueuedRuns} entirely, and heartbeat kept the abandoned claim's lease alive
 * forever with no thread behind it).
 */
@Slf4j
@Component
public class AgentRunWorker {

    private static final int CLAIM_BATCH_SIZE = 2;

    private final TaskStore taskStore;
    private final AgentPlanExecutor agentPlanExecutor;
    private final AgentExecutionRegistry executionRegistry;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final long dispatchRejectBackoffMs;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public AgentRunWorker(TaskStore taskStore,
                          AgentPlanExecutor agentPlanExecutor,
                          AgentExecutionRegistry executionRegistry,
                          @Qualifier("agentExecutor") ThreadPoolTaskExecutor agentExecutor,
                          @Value("${qeploy.agent.worker.dispatch-reject-backoff-ms:5000}")
                          long dispatchRejectBackoffMs) {
        this.taskStore = taskStore;
        this.agentPlanExecutor = agentPlanExecutor;
        this.executionRegistry = executionRegistry;
        this.agentExecutor = agentExecutor;
        this.dispatchRejectBackoffMs = dispatchRejectBackoffMs;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.worker.poll-interval-ms:1000}")
    public void dispatchQueuedRuns() {
        taskStore.recoverExpiredLeases();

        // ADR-Y3 SHOULD: best-effort capacity check before claiming at all. Deliberately racy (the
        // pool's real state can change the instant after this read) — it only needs to be
        // conservative on average, since the per-task try/catch in dispatchOne is the actual hard
        // guarantee regardless of whether this estimate was stale. Its main effect is shrinking the
        // window where a claimed task shows as RUNNING while merely queued inside the executor
        // (audit §4.1's "상태 의미 왜곡" note).
        int freeSlots = estimateFreeExecutorSlots();
        if (freeSlots <= 0) {
            log.debug("[AgentRunWorker] agentExecutor 포화로 이번 폴링은 claim을 생략합니다. workerId={}", workerId);
            return;
        }

        List<String> taskIds = taskStore.claimRunnableTasks(workerId, Math.min(CLAIM_BATCH_SIZE, freeSlots));
        for (String taskId : taskIds) {
            dispatchOne(taskId);
        }
    }

    private void dispatchOne(String taskId) {
        AgentTask task = taskStore.get(taskId);
        AgentPlan plan = taskStore.getPlan(taskId);
        if (task == null || plan == null) {
            taskStore.markFailed(taskId, "저장된 Agent 실행 계획을 찾을 수 없습니다.");
            return;
        }
        // ADR-Y4 — register BEFORE submission, not inside execute() itself. See
        // AgentExecutionRegistry's javadoc for why: a task successfully submitted to the executor
        // but still waiting in its internal queue (not yet running on a thread) must already be
        // heartbeat-protected, or its lease could expire and recoverExpiredLeases would hand the
        // same work to a second claim while the original queued submission is still going to run
        // it later.
        executionRegistry.register(taskId);
        try {
            log.info("[AgentRunWorker] task 실행 위임: taskId={} workerId={}", taskId, workerId);
            agentPlanExecutor.execute(plan, taskId, task.ownerUserId());
        } catch (TaskRejectedException exception) {
            // ADR-Y3: executor pool saturation is backpressure, not this task's failure — release
            // the claim back to QUEUED (attempt untouched) with a short backoff instead of letting
            // the exception propagate out of dispatchQueuedRuns' loop (the D2/G1 bug: one rejected
            // task used to silently strand every other claimed task in this same batch as a
            // RUNNING zombie with no thread behind it).
            handleDispatchFailure(taskId, exception);
        } catch (RuntimeException exception) {
            // Any other pre-submission failure ("제출 전 예외" in design ADR-Y3) gets the same
            // treatment — whatever went wrong, this task must not stay claimed as RUNNING with
            // nothing about to run it.
            handleDispatchFailure(taskId, exception);
        }
    }

    private void handleDispatchFailure(String taskId, RuntimeException exception) {
        executionRegistry.unregister(taskId);
        boolean released = taskStore.releaseClaim(taskId, workerId, dispatchRejectBackoffMs);
        log.warn("[AgentRunWorker] task 제출 실패 — 재대기열로 반환합니다. taskId={} workerId={} released={} cause={}",
                taskId, workerId, released, exception.toString());
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.worker.heartbeat-interval-ms:30000}")
    public void renewLeases() {
        Set<String> registered = executionRegistry.snapshot();
        if (registered.isEmpty()) {
            // ADR-Y4: nothing this JVM is actually executing right now — skip the query entirely.
            // Renewing "every RUNNING row owned by workerId" unconditionally (the pre-#55 behavior)
            // was exactly the D2/G1 bug: a rejected task's lease got kept alive forever with no
            // thread behind it, so recoverExpiredLeases could never reclaim it.
            return;
        }
        taskStore.renewWorkerLeases(workerId, registered);
    }

    private int estimateFreeExecutorSlots() {
        int queueRemaining = agentExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
        int idleThreads = Math.max(0, agentExecutor.getPoolSize() - agentExecutor.getActiveCount());
        return queueRemaining + idleThreads;
    }
}
