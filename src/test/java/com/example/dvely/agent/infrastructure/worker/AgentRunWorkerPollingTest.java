package com.example.dvely.agent.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentPlanExecutor;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkQueuedEvent;
import com.example.dvely.common.worker.WorkerPollGate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * #340 5-1 — 워커가 게이트를 실제로 지키는가. {@code WorkerPollGateTest} 가 백오프 계산 자체를
 * 못박는다면, 이쪽은 "닫힌 게이트에서는 DB 를 한 번도 치지 않는다"와 "깨우면 바로 친다"를 본다.
 */
class AgentRunWorkerPollingTest {

    private static final long BASE_MS = 1_000L;
    private static final long MAX_MS = 30_000L;
    private static final long BACKOFF_MS = 5_000L;

    private final AtomicLong nanos = new AtomicLong();
    private final WorkerPollGate gate = new WorkerPollGate(BASE_MS, MAX_MS, nanos::get);
    private final TaskStore taskStore = mock(TaskStore.class);

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private AgentRunWorker worker() {
        return new AgentRunWorker(
                taskStore,
                mock(AgentPlanExecutor.class),
                mock(AgentMessageService.class),
                new AgentExecutionRegistry(),
                freeExecutor(),
                gate,
                BACKOFF_MS
        );
    }

    private void stubEmptyPoll() {
        when(taskStore.recoverAndClaim(anyString(), anyInt()))
                .thenReturn(new TaskStore.PollBatch(List.of(), List.of()));
    }

    /**
     * 이 단위의 목적 그 자체 — 유휴가 이어지면 <b>틱이 와도 쿼리가 나가지 않는다</b>. 유휴 DB
     * 비용의 대부분이 폴링당 트랜잭션 의례였으므로, 폴링 횟수가 곧 비용이다.
     */
    @Test
    void idleTicksStopTouchingTheDatabaseEntirely() {
        stubEmptyPoll();
        AgentRunWorker worker = worker();

        worker.dispatchQueuedRuns();                       // 1회차: 실제 폴링, 빈손 → 2초 백오프
        verify(taskStore, times(1)).recoverAndClaim(anyString(), anyInt());

        // 그 뒤 1초 틱이 아무리 와도 백오프가 끝나기 전에는 쿼리가 없다.
        advance(1_000);
        worker.dispatchQueuedRuns();
        verify(taskStore, times(1)).recoverAndClaim(anyString(), anyInt());

        advance(1_000);
        worker.dispatchQueuedRuns();                       // 2초 경과 — 여기서만 다시 친다
        verify(taskStore, times(2)).recoverAndClaim(anyString(), anyInt());
    }

    /**
     * 첫 claim 지연. 백오프가 상한(30초)까지 늘어난 뒤에도, 태스크가 큐에 들어가면 <b>바로 다음
     * 틱</b>에 claim 이 나간다 — 사용자가 백오프만큼 기다리는 일은 없다.
     */
    @Test
    void aTaskQueuedWhileFullyBackedOffIsClaimedOnTheVeryNextTick() {
        stubEmptyPoll();
        AgentRunWorker worker = worker();

        // 상한까지 물러나게 만든다.
        for (int i = 0; i < 8; i++) {
            worker.dispatchQueuedRuns();
            advance(MAX_MS);
        }
        worker.dispatchQueuedRuns();
        int pollsWhileIdle = org.mockito.Mockito.mockingDetails(taskStore).getInvocations().size();
        worker.dispatchQueuedRuns();   // 백오프 중 — 쿼리 없음
        assertThat(org.mockito.Mockito.mockingDetails(taskStore).getInvocations().size())
                .as("백오프 중인 틱은 DB 를 치지 않는다")
                .isEqualTo(pollsWhileIdle);

        // 사용자가 메시지를 보냈다 = enqueue 가 커밋됐다.
        gate.onWorkQueued(new WorkQueuedEvent(WorkQueue.AGENT_RUN));

        worker.dispatchQueuedRuns();

        assertThat(org.mockito.Mockito.mockingDetails(taskStore).getInvocations().size())
                .as("깨우기 직후 첫 틱에서 claim 이 나가야 한다 — 지연은 최대 폴링 틱 1회(1초)")
                .isGreaterThan(pollsWhileIdle);
    }

    /**
     * 실행기가 포화라 claim 을 생략한 폴링은 "일이 없다"가 아니다. 자리가 나는 것을 알려줄 신호는
     * 없으므로, 여기서 물러나면 큐에 쌓인 태스크가 백오프 상한만큼 늦게 출발한다.
     */
    @Test
    void aPollSkippedForExecutorSaturationDoesNotBackOff() throws Exception {
        stubEmptyPoll();
        ThreadPoolTaskExecutor saturated = new ThreadPoolTaskExecutor();
        saturated.setCorePoolSize(1);
        saturated.setMaxPoolSize(1);
        saturated.setQueueCapacity(0);
        saturated.initialize();
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
            started.await(2, TimeUnit.SECONDS);
            AgentRunWorker worker = new AgentRunWorker(
                    taskStore, mock(AgentPlanExecutor.class), mock(AgentMessageService.class),
                    new AgentExecutionRegistry(), saturated, gate, BACKOFF_MS);

            worker.dispatchQueuedRuns();
            worker.dispatchQueuedRuns();

            // claim 은 한 번도 안 하지만(claimLimit=0), 회수는 매 틱 돈다 — 물러나지 않았다는 뜻이다.
            verify(taskStore, times(2)).recoverAndClaim(anyString(), eq(0));
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    void aClosedGateSkipsTheStoreCompletely() {
        stubEmptyPoll();
        AgentRunWorker worker = worker();
        worker.dispatchQueuedRuns();
        org.mockito.Mockito.clearInvocations(taskStore);

        worker.dispatchQueuedRuns();   // 아직 백오프 중

        verifyNoInteractions(taskStore);
        verify(taskStore, never()).recoverAndClaim(anyString(), anyInt());
    }

    private ThreadPoolTaskExecutor freeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }
}
