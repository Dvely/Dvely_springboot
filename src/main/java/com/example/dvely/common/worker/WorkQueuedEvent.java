package com.example.dvely.common.worker;

/**
 * 어떤 큐에 워커가 집을 수 있는 일이 들어갔다는 신호(#340 5-1).
 *
 * <p>커밋된 <b>뒤에</b> 전달돼야 한다 — 커밋 전에 깨우면 워커가 아직 보이지 않는 행을 찾다가
 * 헛폴링하고 도로 백오프에 들어간다. 그래서 {@link WorkerPollGate} 의 수신부가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 이다. 발행부는 그냥 {@code publishEvent}
 * 하면 되고, 트랜잭션 안이면 커밋 뒤로 미뤄지고 밖이면 즉시 전달된다.</p>
 */
public record WorkQueuedEvent(WorkQueue queue) {
}
