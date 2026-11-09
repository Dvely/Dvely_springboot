package com.example.dvely.preview.application.service;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 동시에 열려 있는 프리뷰 SSE 프록시 스트림 수의 상한(#624).
 *
 * <p>스트림 하나는 MVC 비동기 실행기의 가상 스레드 하나와 컨테이너로 가는 업스트림 연결 하나를 스트림 수명만큼 쥔다.
 * 실행기에도 상한이 있지만 그것만으로는 안 된다 — 프록시는 업스트림에 먼저 연결한 뒤 스트리밍 본문을 실행기에 넘기는데,
 * 실행기가 그 본문을 거절하면 이미 연 업스트림 연결을 닫을 곳이 없다(SSE 업스트림은 스스로 닫지 않는다). 그래서 업스트림에
 * 연결하기 <b>전에</b> 여기서 자리를 잡고, 없으면 연결하지 않고 503 을 돌려준다.</p>
 *
 * <p>이 상한은 실행기 상한({@code qeploy.web.async.max-concurrent})보다 작아야 한다. 자리는 스트리밍 본문의 끝에서
 * 돌려주는데 실행기의 자리는 그 본문이 반환된 직후에 돌아가므로, 같으면 그 틈에 들어온 요청을 실행기가 거절할 수 있다.
 * 기동 때 확인한다.</p>
 */
@Slf4j
@Component
public class PreviewEventStreamLimiter {

    private final int max;
    private final Semaphore slots;

    public PreviewEventStreamLimiter(
            @Value("${qeploy.preview.event-stream.max-concurrent:100}") int max,
            @Value("${qeploy.web.async.max-concurrent:200}") int executorMax
    ) {
        if (max < 1) {
            throw new IllegalArgumentException("qeploy.preview.event-stream.max-concurrent 는 1 이상이어야 합니다: " + max);
        }
        if (max >= executorMax) {
            throw new IllegalArgumentException("qeploy.preview.event-stream.max-concurrent(" + max
                    + ") 는 qeploy.web.async.max-concurrent(" + executorMax + ") 보다 작아야 합니다 — 같거나 크면 MVC 실행기가"
                    + " 스트림을 거절해 업스트림 연결이 닫히지 않은 채 남을 수 있습니다.");
        }
        this.max = max;
        this.slots = new Semaphore(max);
    }

    /** 자리를 하나 잡는다. 없으면 비어 있다 — 기다리지 않는다. */
    public Optional<Permit> tryAcquire() {
        if (!slots.tryAcquire()) {
            log.warn("[PreviewEventStreamLimiter] 프리뷰 SSE 동시 스트림 상한: max={}", max);
            return Optional.empty();
        }
        return Optional.of(new Permit());
    }

    /** 지금 열려 있는 스트림 수. */
    public int inUse() {
        return max - slots.availablePermits();
    }

    /** 잡은 자리 하나. 여러 번 닫아도 한 번만 돌아간다. */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        }
    }
}
