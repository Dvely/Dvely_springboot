package com.example.dvely.common.worker;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 1초 폴링 워커들의 <b>적응형 백오프 게이트</b>(#340 5-1).
 *
 * <p><b>왜 필요한가.</b> 유휴(사용자 0명·작업 0건) 상태에서 워커 4종이 매초 DB 를 쳐서 분당
 * 1904 쿼리가 나갔다. 그 비용의 대부분은 SELECT 자체가 아니라 트랜잭션 의례다 — 폴링 한 번이
 * {@code SET autocommit=0} → SELECT → {@code COMMIT} → {@code SET autocommit=1} 로 왕복 4배가
 * 된다. 일이 없을 때 폴링 횟수를 줄이면 의례까지 같은 비율로 줄어든다.</p>
 *
 * <p><b>왜 {@code @Scheduled} 간격을 직접 늘리지 않는가.</b> 워커의 {@code fixedDelay} 는 1초로
 * 두고 이 게이트가 <b>DB 를 칠지 말지</b>만 정한다. 스케줄러 스레드가 1초마다 깨어나는 비용은
 * 인메모리 비교 한 번이고, 대신 두 가지를 공짜로 얻는다 — (1) 깨우기 신호가 오면 다음 틱(≤1초)에
 * 곧바로 폴링하므로 <b>첫 claim 지연이 백오프와 무관하게 항상 1초 이내</b>다. 동적
 * {@code Trigger} 로 간격 자체를 늘렸다면 깨우기가 재스케줄까지 해야 했다. (2) 스케줄러
 * 인프라를 손대지 않으므로 워커별로 켜고 끄기가 자유롭다.</p>
 *
 * <p><b>깨우기와 폴백 폴링은 둘 다 필요하다.</b> {@link WorkQueuedEvent} 는 이 JVM 안에서만
 * 도는 인스턴스 로컬 신호다. 다중 인스턴스로 늘리면 <b>다른 인스턴스가 넣은 일</b>은 이 신호를
 * 타고 오지 않는다. 그래서 백오프에는 반드시 상한이 있고(기본 30초), 상한에 닿아도 폴링은
 * 멈추지 않는다 — 최악의 경우에도 남의 일을 30초 안에는 본다.</p>
 *
 * <p><b>깨우기 유실 방지.</b> 폴링이 도는 도중에 신호가 오면, 그 신호가 가리키는 행을 방금 끝난
 * 폴링이 못 봤을 수 있다. 그때 백오프를 늘리면 이미 들어온 일이 최대 상한만큼 늦어진다. 그래서
 * 폴링 시작 시점의 세대를 기억해 두고, 끝났을 때 세대가 달라져 있으면 백오프를 늘리지 않는다.</p>
 */
@Component
public class WorkerPollGate {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final LongSupplier nanoTime;
    private final Map<WorkQueue, QueueState> states = new EnumMap<>(WorkQueue.class);

    @Autowired
    public WorkerPollGate(@Value("${qeploy.worker.poll.base-delay-ms:1000}") long baseDelayMs,
                          @Value("${qeploy.worker.poll.max-delay-ms:30000}") long maxDelayMs) {
        this(baseDelayMs, maxDelayMs, System::nanoTime);
    }

    /** 시계를 주입하는 생성자 — 백오프는 시간에 의존하므로 테스트가 시계를 직접 쥘 수 있어야 한다. */
    public WorkerPollGate(long baseDelayMs, long maxDelayMs, LongSupplier nanoTime) {
        this.baseDelayMs = Math.max(1L, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.nanoTime = nanoTime;
        long now = nanoTime.getAsLong();
        for (WorkQueue queue : WorkQueue.values()) {
            states.put(queue, new QueueState(this.baseDelayMs, now));
        }
    }

    /** 이번 틱에 DB 를 쳐도 되는가. {@code false} 면 워커는 아무 쿼리도 내지 않고 돌아간다. */
    public boolean shouldPoll(WorkQueue queue) {
        return states.get(queue).beginPoll(nanoTime.getAsLong());
    }

    /**
     * 이번 폴링이 일을 찾았다(또는 실행기 포화로 claim 을 미뤘다). 간격을 최소로 되돌린다 —
     * 일이 있는 동안에는 빠르게 도는 것이 맞다.
     */
    public void recordBusy(WorkQueue queue) {
        states.get(queue).recordBusy(nanoTime.getAsLong());
    }

    /** 이번 폴링이 빈손이었다. 다음 간격을 두 배로 늘린다(상한까지). */
    public void recordIdle(WorkQueue queue) {
        states.get(queue).recordIdle(nanoTime.getAsLong(), maxDelayMs);
    }

    /** 백오프를 즉시 풀어 다음 틱에 폴링하게 한다. */
    public void wake(WorkQueue queue) {
        states.get(queue).wake(nanoTime.getAsLong());
    }

    /**
     * enqueue 가 커밋된 뒤 해당 큐의 워커를 깨운다.
     *
     * <p>{@code fallbackExecution = true} 인 이유: 발행부가 항상 트랜잭션 안이라고 가정할 수
     * 없다. 트랜잭션 밖에서 발행된 신호를 조용히 버리면, 그 큐는 백오프 상한만큼 늦게 깨어난다 —
     * 사용자에게는 "눌렀는데 한참 아무 일도 안 일어남"으로 보인다. 밖이면 즉시 처리한다.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkQueued(WorkQueuedEvent event) {
        wake(event.queue());
    }

    /**
     * 큐 하나의 백오프 상태. 갱신은 폴링 스레드(스케줄러)와 깨우기 스레드(요청·실행기) 양쪽에서
     * 오지만 초당 몇 번 수준이라, 눈에 보이게 맞는 {@code synchronized} 를 쓴다 — 여기서 lock-free
     * 로 얻을 것은 없고, 잃을 것(깨우기 유실)은 사용자에게 최대 30초로 보인다.
     */
    private static final class QueueState {

        private final long baseDelayMs;
        private long delayMs;
        private long nextPollAtNanos;
        private long generation;
        private long polledGeneration;

        private QueueState(long baseDelayMs, long now) {
            this.baseDelayMs = baseDelayMs;
            this.delayMs = baseDelayMs;
            this.nextPollAtNanos = now;   // 기동 직후 첫 틱은 무조건 폴링한다
        }

        private synchronized boolean beginPoll(long now) {
            if (now - nextPollAtNanos < 0) {
                return false;
            }
            polledGeneration = generation;
            return true;
        }

        private synchronized void recordBusy(long now) {
            delayMs = baseDelayMs;
            nextPollAtNanos = now;
        }

        private synchronized void recordIdle(long now, long max) {
            if (generation != polledGeneration) {
                // 폴링이 도는 중에 깨우기가 왔다. 그 일을 이 폴링이 못 봤을 수 있으므로 백오프를
                // 늘리지 않는다 — wake 가 이미 간격과 다음 시각을 최소로 돌려놨다.
                return;
            }
            delayMs = Math.min(delayMs * 2, max);
            nextPollAtNanos = now + TimeUnit.MILLISECONDS.toNanos(delayMs);
        }

        private synchronized void wake(long now) {
            generation++;
            delayMs = baseDelayMs;
            nextPollAtNanos = now;
        }
    }
}
