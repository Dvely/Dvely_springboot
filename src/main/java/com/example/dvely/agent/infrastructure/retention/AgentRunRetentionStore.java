package com.example.dvely.agent.infrastructure.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code agent_runs} · {@code agent_run_events} 보존 스윕의 DB 접근(#338).
 *
 * <p>기존 {@code SpringDataAgentRunRepository} 에 붙이지 않고 전용 클래스를 새로 둔 이유는
 * 두 가지다. (1) agent 도메인은 병렬 작업 구역이라 공유 인터페이스를 건드리면 충돌 표면이 커진다.
 * (2) 여기 있는 두 문장은 "무엇을 지울 수 있는가"라는 보존 정책 지식이라, 일반 조회·저장 경로와
 * 섞이면 실수로 재사용되기 쉽다.</p>
 *
 * <h2>왜 행을 지우지 않고 컬럼만 비우는가</h2>
 * <p>{@code preview_sessions.task_id} 와 {@code project_changes.task_id} 가 {@code agent_runs}
 * 를 FK 로 참조한다. 그래서 run 행 자체는 지울 수 없다 — 지우려면 프리뷰 이력과 변경 이력을 함께
 * 잃는다. 대신 용량의 대부분을 차지하는 {@code plan_json LONGTEXT} 와 {@code failure_log TEXT}
 * 만 비운다. run 의 상태·시각 같은 뼈대는 남으므로 이력 조회는 계속 동작한다.</p>
 */
@Repository
@RequiredArgsConstructor
public class AgentRunRetentionStore {

    /** TaskStore.TERMINAL_STATUSES 와 같은 집합. 진행 중인 run 은 절대 건드리지 않는다. */
    private static final List<String> TERMINAL_STATUSES = List.of("DONE", "FAILED", "CANCELLED");

    private final EntityManager entityManager;

    /**
     * 터미널 run 중 {@code cutoff} 이전에 만들어진 것의 큰 텍스트 컬럼을 비운다.
     *
     * <p>{@code (plan_json IS NOT NULL OR failure_log IS NOT NULL)} 조건은 장식이 아니라
     * <b>반복 종료 조건</b>이다. MySQL Connector/J 는 기본값({@code useAffectedRows=false})에서
     * 바뀐 행이 아니라 <b>조건에 맞은</b> 행 수를 돌려준다. 이 조건이 없으면 이미 비운 행이
     * 계속 매칭돼 호출자의 "0 이 될 때까지" 루프가 영원히 끝나지 않는다.</p>
     *
     * @return 이번 배치에서 비운 행 수
     */
    @Transactional
    public int blankTerminalPayloadsBatch(LocalDateTime cutoff, int batchSize) {
        Query query = entityManager.createNativeQuery("""
                update agent_runs
                   set plan_json = null, failure_log = null
                 where status in (:statuses)
                   and created_at < :cutoff
                   and (plan_json is not null or failure_log is not null)
                 limit :batchSize
                """);
        query.setParameter("statuses", TERMINAL_STATUSES);
        query.setParameter("cutoff", cutoff);
        query.setParameter("batchSize", batchSize);
        return query.executeUpdate();
    }

    /**
     * 같은 run 들의 진행 이벤트를 지운다. 이벤트는 진행 상황을 실시간으로 보여주기 위한 것이라
     * run 이 끝나고 보존 기간이 지나면 읽히지 않는다. {@code agent_run_events} 를 참조하는
     * FK 는 없으므로 여기서는 행을 지울 수 있다.
     *
     * @return 이번 배치에서 지운 행 수
     */
    @Transactional
    public int deleteTerminalEventsBatch(LocalDateTime cutoff, int batchSize) {
        Query query = entityManager.createNativeQuery("""
                delete from agent_run_events
                 where task_id in (
                     select task_id from agent_runs
                      where status in (:statuses) and created_at < :cutoff
                 )
                 limit :batchSize
                """);
        query.setParameter("statuses", TERMINAL_STATUSES);
        query.setParameter("cutoff", cutoff);
        query.setParameter("batchSize", batchSize);
        return query.executeUpdate();
    }
}
