package com.example.dvely.agent.application.stream;

/**
 * 태스크에 진행 이벤트 한 건이 적재됐다는 신호(#339 4-2).
 *
 * <p>커밋된 <b>뒤에</b> 전달돼야 한다 — 커밋 전에 깨우면 스트림이 아직 보이지 않는 행을 찾다가
 * 헛조회하고 도로 눕는다. 그래서 {@link AgentEventBus} 의 수신부가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 이다. 발행부는 그냥 {@code publishEvent}
 * 하면 되고, 트랜잭션 안이면 커밋 뒤로 미뤄지고 밖이면 즉시 전달된다.</p>
 */
public record AgentEventAppendedEvent(String taskId) {
}
