package com.example.dvely.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * #339 4-3 — {@code agentEventExecutor} 가 실제로 동시에 돌려 주는가, 그리고 그 대가로 종료
 * 동작을 잃지 않았는가.
 *
 * <p>예전 설정은 core2/max10/queue100 이었다. {@code ThreadPoolExecutor} 는 큐가 가득 차야
 * 코어를 넘겨 스레드를 늘리므로 실질 동시 실행이 2 였고, 3 번째 스트림부터는 앞 스트림이 5분을
 * 채울 때까지 큐에서 시작조차 되지 않았다. 아래 첫 테스트가 그 회귀를 막는다.</p>
 */
class AgentEventExecutorTest {

    @Test
    void 다섯_건이_전부_곧바로_시작된다() throws Exception {
        SimpleAsyncTaskExecutor executor = new AsyncConfig().agentEventExecutor(50);
        CountDownLatch started = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 5; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // 예전 풀이었다면 2 건만 시작되고 나머지 3 건은 큐에서 대기해 여기서 실패한다.
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void 종료는_인터럽트로_깨워_스트림이_스스로_정리하게_한다() throws Exception {
        SimpleAsyncTaskExecutor executor = new AsyncConfig().agentEventExecutor(50);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleanedUp = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        executor.execute(() -> {
            started.countDown();
            try {
                // 스트림 루프가 이벤트를 기다리는 자리. 최대 5분까지 여기 머문다.
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException expected) {
                // AgentEventStreamService.stream 이 emitter.complete() 하는 경로.
                interrupted.set(true);
                cleanedUp.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        executor.close();
        long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(cleanedUp.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        // 스트림 수명(5분)만큼 종료가 멈추지 않는다 — 유예는 정리가 끝날 시간까지다.
        assertThat(closeMillis).isLessThan(TimeUnit.SECONDS.toMillis(10));
    }

    @Test
    void 상한에_닿으면_제출자를_막지_않고_거부한다() throws Exception {
        SimpleAsyncTaskExecutor executor = new AsyncConfig().agentEventExecutor(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            // 요청 스레드가 앞 스트림의 수명만큼 묶이면 안 된다 — 거부는 즉시 돌아와야 한다.
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1_000L);
        } finally {
            release.countDown();
            executor.close();
        }
    }
}
