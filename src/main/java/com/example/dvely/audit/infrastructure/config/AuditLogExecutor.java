package com.example.dvely.audit.infrastructure.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 감사 로그 전용 실행기. 스레드 하나와 유한한 큐를 감싼다 — 근거는 {@link AuditExecutorConfig}.
 *
 * <p>{@link ThreadPoolTaskExecutor} 를 그대로 빈으로 내놓지 않고 감싼 이유는 {@link #awaitDrained}
 * 하나 때문이다. 비동기로 바꾸면서 새로 생긴 문제는 롤백이 아니라 <b>가시성 지연</b>이다 —
 * {@code record()} 가 돌아온 시점에 행은 아직 없을 수 있다. 그 경계를 테스트가 넘는 방법이
 * {@code Thread.sleep} 이나 폴링이면 CI 부하에 따라 다시 흔들리므로, 큐가 실제로 비었다는 것을
 * 확정적으로 알 수 있는 지점을 만들어 둔다.</p>
 */
public class AuditLogExecutor implements Executor, DisposableBean {

    private final ThreadPoolTaskExecutor delegate;

    AuditLogExecutor(ThreadPoolTaskExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(task);
    }

    /**
     * 이 호출 시점까지 넣어둔 감사 쓰기가 전부 끝날 때까지 기다린다.
     *
     * <p>원리는 FIFO 큐 + 워커 1개라는 성질 그 자체다. 지금 넣는 표식 작업이 워커에서 실행됐다면
     * 그보다 먼저 들어간 작업은 이미 다 끝난 것이다 — 시간을 재지 않으므로 부하에 흔들리지 않는다.</p>
     *
     * <p>큐가 가득 차 표식이 호출 스레드에서 실행된 경우에는 앞선 작업이 아직 남아 있을 수 있어
     * {@code false} 를 돌려준다. 조용히 참을 돌려주면 그 순간부터 이 메서드가 거짓말을 하게 된다.</p>
     *
     * @return 큐가 실제로 비었으면 true. 시간 초과이거나 위 예외 상황이면 false
     */
    public boolean awaitDrained(Duration timeout) {
        Thread caller = Thread.currentThread();
        AtomicBoolean ranOnWorker = new AtomicBoolean();
        CountDownLatch marker = new CountDownLatch(1);

        delegate.execute(() -> {
            ranOnWorker.set(Thread.currentThread() != caller);
            marker.countDown();
        });

        try {
            return marker.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && ranOnWorker.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 종료 시 큐에 남은 감사 기록을 흘려보내고 내려간다({@code setWaitForTasksToCompleteOnShutdown}).
     * 감싸면서 이 전파를 빠뜨리면 배포마다 마지막 몇 건이 조용히 사라진다.
     */
    @Override
    public void destroy() {
        delegate.shutdown();
    }
}
