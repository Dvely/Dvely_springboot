package com.example.dvely.agent.application.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * #339 4-2 — 깨우기 신호선의 계약.
 *
 * <p>여기서 증명해야 하는 것은 "깨우면 깨어난다"가 아니라 <b>신호가 유실되지 않는다</b>이다.
 * 스트림은 DB 를 읽는 동안에는 대기하고 있지 않으므로, 그 사이에 온 신호가 사라지면 이미 들어온
 * 이벤트가 폴백 간격(5초)만큼 늦어진다.</p>
 */
class AgentEventBusTest {

    private final AgentEventBus bus = new AgentEventBus();

    @Test
    void 신호가_오면_대기가_상한을_기다리지_않고_끊긴다() throws Exception {
        try (AgentEventBus.Subscription subscription = bus.subscribe("task-1")) {
            CountDownLatch woke = new CountDownLatch(1);
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    if (subscription.await(30_000)) {
                        woke.countDown();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            // 대기에 확실히 들어간 뒤 깨운다.
            awaitWaiting(waiter);
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));

            assertThat(woke.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 대기하지_않는_동안_온_신호는_토큰으로_남아_다음_대기를_즉시_깨운다() throws Exception {
        try (AgentEventBus.Subscription subscription = bus.subscribe("task-1")) {
            // 스트림이 DB 를 읽고 있는 시점 — 아직 await 에 들어가지 않았다.
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));

            long startedAt = System.nanoTime();
            assertThat(subscription.await(30_000)).isTrue();
            assertThat(System.nanoTime() - startedAt).isLessThan(TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test
    void 신호가_여러_번_와도_재조회_한_번이면_충분하므로_토큰은_하나만_남는다() throws Exception {
        try (AgentEventBus.Subscription subscription = bus.subscribe("task-1")) {
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));

            assertThat(subscription.await(0)).isTrue();
            assertThat(subscription.await(0)).isFalse();
        }
    }

    @Test
    void 신호가_없으면_상한까지만_기다렸다_돌아온다() throws Exception {
        try (AgentEventBus.Subscription subscription = bus.subscribe("task-1")) {
            long startedAt = System.nanoTime();
            assertThat(subscription.await(150)).isFalse();
            assertThat(System.nanoTime() - startedAt).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(140));
        }
    }

    @Test
    void 같은_태스크를_보는_구독은_전부_깨어난다() throws Exception {
        try (AgentEventBus.Subscription first = bus.subscribe("task-1");
             AgentEventBus.Subscription second = bus.subscribe("task-1")) {
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-1"));

            assertThat(first.await(0)).isTrue();
            assertThat(second.await(0)).isTrue();
        }
    }

    @Test
    void 다른_태스크의_신호는_깨우지_않는다() throws Exception {
        try (AgentEventBus.Subscription subscription = bus.subscribe("task-1")) {
            bus.onAgentEventAppended(new AgentEventAppendedEvent("task-2"));

            assertThat(subscription.await(0)).isFalse();
        }
    }

    @Test
    void 스트림이_끝나면_구독이_남지_않는다() {
        AgentEventBus.Subscription subscription = bus.subscribe("task-1");
        assertThat(bus.subscriberCount("task-1")).isEqualTo(1);

        subscription.close();

        assertThat(bus.subscriberCount("task-1")).isZero();
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        for (int i = 0; i < 200 && thread.getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(10);
        }
    }
}
