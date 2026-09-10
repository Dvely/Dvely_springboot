package com.example.dvely.common.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * #340 5-1 — 적응형 백오프의 계약.
 *
 * <p>시계를 주입해 벽시계에 의존하지 않는다. 백오프는 "몇 초 뒤"가 본질이라 실제로 재우면
 * 테스트가 느려지고 흔들린다.</p>
 */
class WorkerPollGateTest {

    private static final long BASE_MS = 1_000L;
    private static final long MAX_MS = 30_000L;

    private final AtomicLong nanos = new AtomicLong();
    private final WorkerPollGate gate = new WorkerPollGate(BASE_MS, MAX_MS, nanos::get);

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void firstTickAfterStartupAlwaysPolls() {
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
    }

    @Test
    void idlePollsBackOffByDoublingUpToTheCap() {
        // 유휴가 이어지면 간격이 1 → 2 → 4 → 8 → 16 → 30(상한) 초로 늘어난다.
        long[] expectedDelaysMs = {2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L};
        for (long expected : expectedDelaysMs) {
            assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
            gate.recordIdle(WorkQueue.AGENT_RUN);

            // 기대 간격 직전까지는 닫혀 있고, 지나면 열린다.
            advance(expected - 1);
            assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN))
                    .as("%dms 백오프가 끝나기 전에는 DB 를 치지 않는다", expected)
                    .isFalse();
            advance(1);
        }
    }

    @Test
    void findingWorkResetsTheIntervalToTheMinimum() {
        gate.shouldPoll(WorkQueue.AGENT_RUN);
        gate.recordIdle(WorkQueue.AGENT_RUN);
        gate.shouldPoll(WorkQueue.AGENT_RUN);
        advance(2_000);
        gate.shouldPoll(WorkQueue.AGENT_RUN);
        gate.recordIdle(WorkQueue.AGENT_RUN);   // 여기서 간격은 4초

        advance(4_000);
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
        gate.recordBusy(WorkQueue.AGENT_RUN);

        // 일을 찾았으니 즉시 다시 열려 있어야 한다 — 큐에 더 남아 있을 수 있다.
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
        gate.recordIdle(WorkQueue.AGENT_RUN);
        advance(1_999);
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN))
                .as("간격이 최소(1초)로 초기화됐으므로 다음 백오프는 4초가 아니라 2초다")
                .isFalse();
        advance(1);
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
    }

    /**
     * 이 단위의 핵심 계약 — 백오프가 상한까지 늘어나 있어도, 일이 들어오면 <b>즉시</b> 열린다.
     * 이것이 없으면 사용자가 메시지를 보낸 뒤 최대 30초를 기다리게 된다.
     */
    @Test
    void wakingUpOpensTheGateImmediatelyNoMatterHowFarItHadBackedOff() {
        for (int i = 0; i < 10; i++) {
            gate.shouldPoll(WorkQueue.AGENT_RUN);
            gate.recordIdle(WorkQueue.AGENT_RUN);
            advance(MAX_MS);
        }
        gate.shouldPoll(WorkQueue.AGENT_RUN);
        gate.recordIdle(WorkQueue.AGENT_RUN);   // 상한(30초) 백오프에 들어간 상태
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isFalse();

        gate.onWorkQueued(new WorkQueuedEvent(WorkQueue.AGENT_RUN));

        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN))
                .as("enqueue 커밋 뒤에는 백오프 값과 무관하게 다음 틱에 폴링해야 한다")
                .isTrue();
    }

    @Test
    void wakingOneQueueDoesNotDisturbAnother() {
        gate.shouldPoll(WorkQueue.AGENT_RUN);
        gate.recordIdle(WorkQueue.AGENT_RUN);
        gate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY);
        gate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);

        gate.onWorkQueued(new WorkQueuedEvent(WorkQueue.AGENT_RUN));

        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();
        assertThat(gate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY))
                .as("배포 큐에 일이 들어왔다고 웹훅 워커까지 깨울 이유는 없다")
                .isFalse();
    }

    /**
     * 폴링이 도는 도중에 깨우기가 오는 경우. 그 폴링은 방금 커밋된 행을 못 봤을 수 있으므로,
     * 빈손으로 끝났다고 백오프를 늘리면 이미 들어온 일이 최대 상한만큼 늦어진다.
     */
    @Test
    void aWakeupThatArrivesMidPollIsNotSwallowedByThatPollsBackoff() {
        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN)).isTrue();   // 폴링 시작

        gate.onWorkQueued(new WorkQueuedEvent(WorkQueue.AGENT_RUN)); // 폴링 도중 enqueue 커밋
        gate.recordIdle(WorkQueue.AGENT_RUN);                        // 이 폴링은 빈손이었다

        assertThat(gate.shouldPoll(WorkQueue.AGENT_RUN))
                .as("폴링 중에 온 깨우기는 그 폴링의 백오프에 묻히면 안 된다")
                .isTrue();
    }
}
