package com.example.dvely.agent.application.stream;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * SSE 스트림을 <b>이벤트가 적재되는 순간</b> 깨우는 인스턴스 로컬 신호선(#339 4-2).
 *
 * <p><b>왜 필요한가.</b> 스트림 루프가 매초 DB 를 쳐서 새 이벤트가 있는지 물었다. 이벤트는
 * 대부분의 시간 동안 오지 않으므로 그 조회는 거의 전부 헛것이었고, 스트림 하나가 분당 180 쿼리를
 * 썼다. 이벤트를 넣는 쪽이 기다리는 쪽을 깨워 주면 그 헛조회가 통째로 사라진다.</p>
 *
 * <p><b>깨우기만으로는 안 된다.</b> 이 신호는 이 JVM 안에서만 돈다. 다중 인스턴스로 늘리면
 * <b>다른 인스턴스가 적재한 이벤트</b>는 이 신호를 타고 오지 않는다. 그래서
 * {@link #await(long)} 에는 반드시 상한이 있고, 호출부는 신호가 없어도 그 상한마다 DB 를 다시
 * 본다 — 최악의 경우에도 남의 이벤트를 그 간격 안에는 본다.</p>
 *
 * <p><b>깨우기 유실 방지.</b> 스트림이 DB 를 읽고 있는 사이에 신호가 오면, 그 이벤트를 방금 끝난
 * 조회가 못 봤을 수 있다. 그때 신호를 흘려 버리면 이미 들어온 이벤트가 폴백 간격만큼 늦어진다.
 * 그래서 신호는 <b>1칸짜리 큐에 토큰으로 남는다</b> — 대기에 들어가는 순간 그 토큰이 곧바로
 * 소모되어 재조회로 이어진다. 폴링 워커의 세대 번호(WorkerPollGate)와 목적이 같은데, 여기서는
 * 대기자가 자기 스레드를 쥐고 블로킹할 수 있어 토큰이 남는 것만으로 충분하다. 칸이 하나인 것은
 * 신호가 몇 번 오든 재조회 한 번이면 밀린 이벤트를 전부 가져오기 때문이다.</p>
 */
@Component
public class AgentEventBus {

    private static final Object SIGNAL = new Object();

    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 이 태스크의 이벤트 적재를 구독한다. 호출부는 반드시 {@link Subscription#close()} 해야 한다 —
     * 안 하면 끝난 스트림의 구독이 맵에 남아 그대로 누수다.
     */
    public Subscription subscribe(String taskId) {
        Subscription subscription = new Subscription(taskId);
        // add 를 compute 안에서 한다 — 밖에서 하면 동시에 도는 unsubscribe 가 "빈 집합"으로 보고
        // 키를 지워 버려, 방금 등록한 구독이 아무 신호도 못 받는 창이 생긴다.
        subscriptions.compute(taskId, (key, existing) -> {
            Set<Subscription> target = existing == null ? ConcurrentHashMap.newKeySet() : existing;
            target.add(subscription);
            return target;
        });
        return subscription;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentEventAppended(AgentEventAppendedEvent event) {
        Set<Subscription> waiting = subscriptions.get(event.taskId());
        if (waiting != null) {
            waiting.forEach(Subscription::signal);
        }
    }

    /** 누수 확인용 — 스트림이 끝난 뒤 구독이 남아 있지 않은지 테스트가 본다. */
    int subscriberCount(String taskId) {
        Set<Subscription> waiting = subscriptions.get(taskId);
        return waiting == null ? 0 : waiting.size();
    }

    private void unsubscribe(Subscription subscription) {
        subscriptions.computeIfPresent(subscription.taskId, (key, waiting) -> {
            waiting.remove(subscription);
            return waiting.isEmpty() ? null : waiting;
        });
    }

    /** 스트림 하나의 구독. */
    public final class Subscription implements AutoCloseable {

        private final String taskId;
        private final BlockingQueue<Object> pending = new ArrayBlockingQueue<>(1);

        private Subscription(String taskId) {
            this.taskId = taskId;
        }

        /**
         * 신호가 올 때까지, 늦어도 {@code timeoutMs} 까지 기다린다.
         *
         * <p>인터럽트를 삼키지 않는다 — 호출부(스트림 루프)에게 인터럽트는 곧 정상 종료 경로다.</p>
         *
         * @return 신호를 받아 깨어났으면 {@code true}, 시간이 다 돼 깨어났으면 {@code false}
         */
        public boolean await(long timeoutMs) throws InterruptedException {
            return pending.poll(timeoutMs, TimeUnit.MILLISECONDS) != null;
        }

        private void signal() {
            // offer 는 칸이 차 있으면 그냥 버린다 — 이미 "볼 것이 있다"는 토큰이 있으므로 충분하다.
            pending.offer(SIGNAL);
        }

        @Override
        public void close() {
            unsubscribe(this);
        }
    }
}
