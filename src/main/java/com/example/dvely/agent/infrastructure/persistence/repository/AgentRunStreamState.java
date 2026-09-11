package com.example.dvely.agent.infrastructure.persistence.repository;

/**
 * SSE 스트림 루프가 매 틱 필요한 <b>전부</b>(#339 4-1) — 태스크 상태와 마지막 이벤트 번호.
 *
 * <p>엔티티를 통째로 읽으면 {@code plan_json LONGTEXT} 까지 딸려 온다. 루프는 그 값을 한 번도
 * 쓰지 않으면서 매초 실어 날랐다. 새 이벤트가 있는지는 {@code lastEventId} 비교로 충분하므로,
 * 이 두 스칼라만 한 쿼리로 가져온다.</p>
 *
 * @param lastEventId 이벤트가 하나도 없으면 {@code null}
 */
public record AgentRunStreamState(String status, Long lastEventId) {
}
