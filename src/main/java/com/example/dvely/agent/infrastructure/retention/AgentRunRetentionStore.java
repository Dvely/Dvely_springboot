package com.example.dvely.agent.infrastructure.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
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
 *
 * <h2>잠금 — 왜 READ COMMITTED 인가(#618)</h2>
 * <p>기본 격리(REPEATABLE READ)에서 InnoDB 는 UPDATE·DELETE 가 <b>검사한</b> 모든 행에 next-key 잠금을 잡고
 * 트랜잭션 끝까지 쥔다. 두 문장 모두 조건에 맞는 행을 찾느라 많은 행을 훑으므로, 실측(런 10만·이벤트 60만)에서
 * 비우기 배치 하나가 agent_runs 행 10만 개를 잠갔다 — 이미 비운 행과 진행 중인 run 까지. 그 사이 실행 중 태스크의
 * {@code lease_until} 갱신이 3.04초 기다렸고 완료 UPDATE 는 Lock wait timeout 으로 실패했다.</p>
 *
 * <p>READ COMMITTED 에서는 조건에 맞지 않은 행의 잠금을 곧바로 놓고 갭 잠금도 잡지 않는다. 같은 실측에서 잠금
 * 59만 → 1, 리스 갱신 대기 3.04초 → 1.8ms 였다. 보존 스윕은 "지금 터미널이고 기간을 넘긴 행" 만 다루고 한 문장씩
 * 끝나므로 팬텀을 막을 이유가 없다. 전제: binlog 가 ROW(또는 MIXED) 여야 한다 — STATEMENT 에서 RC 의 DML 은
 * MySQL 이 거부한다. MySQL 8.0 기본값이 ROW 다.</p>
 *
 * <p>다만 RC 가 잠긴 행을 건너뛰는 것(semi-consistent read)은 <b>클러스터 인덱스를 훑을 때만</b>이다. 보조 인덱스로 읽으면
 * 범위 안의 잠긴 행을 비울 대상이 아니어도 기다린다. 그래서 비우기 UPDATE 는 비울 행만 담긴 범위를 읽는다 —
 * {@link #blankTerminalPayloadsBatch} 참고.</p>
 */
@Repository
@RequiredArgsConstructor
public class AgentRunRetentionStore {

    /** TaskStore.TERMINAL_STATUSES 와 같은 집합. 진행 중인 run 은 절대 건드리지 않는다. */
    private static final List<String> TERMINAL_STATUSES = List.of("DONE", "FAILED", "CANCELLED");

    private final EntityManager entityManager;

    /**
     * 터미널 run 중 {@code cutoff} 이전에 만들어진 것의 큰 텍스트 컬럼을 비우고, 비운 시각을 {@code payload_purged_at} 에
     * 남긴다.
     *
     * <p><b>읽는 행 = 비울 행</b>(#618 후속). {@code idx_agent_runs_retention (status, payload_purged_at, created_at)} 의
     * "터미널 · 아직 안 비움 · 보존 기간을 넘김" 범위만 읽는다. 비우면 시각이 남아 범위에서 빠진다.</p>
     * <ul>
     *   <li>예전에는 탈 수 있는 인덱스가 {@code (status, …)} 뿐이라, 계획에 따라 터미널 run 전부를 읽었다. 합성 10만
     *       행(비울 행 12개)에서 배치마다 99,909행을 읽었다. 같은 데이터에서 지금은 11행, 2.7ms 다.</li>
     *   <li>보조 인덱스로 읽으면 RC 에서도 잠긴 행을 건너뛰지 못한다(클래스 javadoc). 그래서 범위 안에 든 최근 DONE
     *       행이나 이미 비운 행을 다른 트랜잭션이 쥐고 있으면 기다렸다. 이제 그런 행은 범위에 없다.</li>
     *   <li>인덱스를 힌트로 고정한다. 이 문제는 "옵티마이저가 어느 계획을 고르느냐에 따라 스윕의 잠금·비용이 달라진다"
     *       였다. 그 변수를 없앤다. 인덱스 이름이 바뀌면 이 문장이 실패하고, {@code RetentionSweepTest} 가 실제 빈으로
     *       부르므로 거기서 드러난다.</li>
     * </ul>
     *
     * <p>{@code payload_purged_at IS NULL} 은 <b>반복 종료 조건</b>이기도 하다. MySQL Connector/J 는 기본값
     * ({@code useAffectedRows=false})에서 바뀐 행이 아니라 <b>조건에 맞은</b> 행 수를 돌려준다. 비운 행이 다시 맞으면
     * 호출자의 "0 이 될 때까지" 루프가 끝나지 않는다. 예전에는 {@code plan_json IS NOT NULL OR failure_log IS NOT NULL}
     * 이 이 일을 했다. 그 조건으로 비운 옛 run 은 시각이 없으므로, 배포 뒤 첫 스윕이 한 번 더 맞춰 시각을 남긴다.</p>
     *
     * <p>격리 수준이 READ COMMITTED 인 이유는 클래스 javadoc 의 "잠금" 절을 본다.</p>
     *
     * @return 이번 배치에서 비운 행 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int blankTerminalPayloadsBatch(LocalDateTime cutoff, int batchSize) {
        Query query = entityManager.createNativeQuery("""
                update agent_runs force index (idx_agent_runs_retention)
                   set plan_json = null, failure_log = null, payload_purged_at = now()
                 where status in (:statuses)
                   and payload_purged_at is null
                   and created_at < :cutoff
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
     * <p>{@code e.created_at < :cutoff} 는 이벤트 자신도 보존 기간을 넘겼다는 조건이자, 인덱스
     * {@code idx_agent_run_events_created} 로 범위만 읽게 하는 조건이다(#618). 이것이 없으면 서브쿼리만으로
     * 거르느라 매 배치 테이블 전체를 훑었다 — 마지막 0 건 배치까지. 이벤트는 run 이 진행되는 몇 분 사이에
     * 쌓이므로, run 이 보존 기간을 넘겼으면 그 이벤트도 거의 다 넘겼다. 경계에 걸린 몇 건은 다음 스윕이 지운다.</p>
     *
     * @return 이번 배치에서 지운 행 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int deleteTerminalEventsBatch(LocalDateTime cutoff, int batchSize) {
        Query query = entityManager.createNativeQuery("""
                delete from agent_run_events
                 where created_at < :cutoff
                   and task_id in (
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
