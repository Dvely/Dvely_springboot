package com.example.dvely.agent.infrastructure.worker;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.orchestrator.AgentPlanExecutor;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkerPollGate;
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
    private final AgentMessageService agentMessageService;
    private final AgentExecutionRegistry executionRegistry;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final WorkerPollGate pollGate;
    private final long dispatchRejectBackoffMs;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public AgentRunWorker(TaskStore taskStore,
                          AgentPlanExecutor agentPlanExecutor,
                          AgentMessageService agentMessageService,
                          AgentExecutionRegistry executionRegistry,
                          @Qualifier("agentExecutor") ThreadPoolTaskExecutor agentExecutor,
                          WorkerPollGate pollGate,
                          @Value("${qeploy.agent.worker.dispatch-reject-backoff-ms:5000}")
                          long dispatchRejectBackoffMs) {
        this.taskStore = taskStore;
        this.agentPlanExecutor = agentPlanExecutor;
        this.agentMessageService = agentMessageService;
        this.executionRegistry = executionRegistry;
        this.agentExecutor = agentExecutor;
        this.pollGate = pollGate;
        this.dispatchRejectBackoffMs = dispatchRejectBackoffMs;
    }

    @Scheduled(fixedDelayString = "${qeploy.agent.worker.poll-interval-ms:1000}")
    public void dispatchQueuedRuns() {
        // #340 5-1: 틱은 여전히 1초마다 오지만, 일이 없는 동안에는 DB 를 치지 않는다. 게이트가
        // 닫혀 있으면 여기서 끝이고 쿼리는 한 건도 나가지 않는다.
        if (!pollGate.shouldPoll(WorkQueue.AGENT_RUN)) {
            return;
        }

        // ADR-Y3 SHOULD: best-effort capacity check before claiming at all. Deliberately racy (the
        // pool's real state can change the instant after this read) — it only needs to be
        // conservative on average, since the per-task try/catch in dispatchOne is the actual hard
        // guarantee regardless of whether this estimate was stale. Its main effect is shrinking the
        // window where a claimed task shows as RUNNING while merely queued inside the executor
        // (audit §4.1's "상태 의미 왜곡" note).
        int freeSlots = estimateFreeExecutorSlots();
        boolean saturated = freeSlots <= 0;
        if (saturated) {
            log.debug("[AgentRunWorker] agentExecutor 포화로 이번 폴링은 claim을 생략합니다. workerId={}", workerId);
        }

        TaskStore.PollBatch batch;
        try {
            // 포화여도 회수는 돌린다(claimLimit=0). 포화를 이유로 폴링을 통째로 건너뛰면 좀비
            // 리스가 그만큼 오래 남는다.
            batch = taskStore.recoverAndClaim(workerId, saturated ? 0 : Math.min(CLAIM_BATCH_SIZE, freeSlots));
        } catch (RuntimeException exception) {
            // DB 가 흔들리는 동안 매초 같은 쿼리를 다시 던져봐야 소용이 없다 — 물러나며 재시도한다.
            pollGate.recordIdle(WorkQueue.AGENT_RUN);
            throw exception;
        }

        // 포화로 claim 을 생략한 것은 "일이 없다"가 아니다. 자리가 나는 것을 알려줄 신호는 없으므로
        // 여기서 물러나면 큐에 쌓인 태스크가 백오프 상한만큼 늦게 출발한다.
        if (saturated || batch.touchedWork()) {
            pollGate.recordBusy(WorkQueue.AGENT_RUN);
        } else {
            pollGate.recordIdle(WorkQueue.AGENT_RUN);
        }

        notifyLeaseExhausted(batch.leaseExhausted());
        for (String taskId : batch.claimed()) {
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

    /**
     * 리스가 만료돼 복구 횟수까지 소진한 태스크를 사용자에게 알린다.
     *
     * 이 경로는 사람이 만든 실패가 아니다 — 워커가 죽거나 서버가 재시작돼 실행이 통째로
     * 사라진 경우다. 그래서 다른 실패 경로와 달리 아무도 채팅에 말을 남기지 않았고, 사용자에게는
     * 진행 표시만 사라진 멈춘 화면으로 보였다. 종료 상태는 모두 대화에 흔적을 남겨야 한다.
     *
     * 한 건의 실패가 나머지를 막지 않게 태스크 단위로 격리한다 — 이 클래스의 다른 루프와 같은
     * 원칙이다(ADR-Y3).
     */
    private void notifyLeaseExhausted(List<String> taskIds) {
        for (String taskId : taskIds) {
            try {
                AgentTask task = taskStore.get(taskId);
                if (task == null) {
                    continue;
                }
                agentMessageService.appendAssistant(
                        task.conversationId(),
                        "실행이 중단되어 작업을 종료했습니다. 다시 요청해주세요.",
                        ChatMessageKind.TASK_CANCELLED,
                        task.taskId()
                );
            } catch (Exception exception) {
                log.warn("[AgentRunWorker] 리스 소진 안내 실패 — taskId={}", taskId, exception);
            }
        }
    }

    private int estimateFreeExecutorSlots() {
        int queueRemaining = agentExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
        int idleThreads = Math.max(0, agentExecutor.getPoolSize() - agentExecutor.getActiveCount());
        return queueRemaining + idleThreads;
    }
}
