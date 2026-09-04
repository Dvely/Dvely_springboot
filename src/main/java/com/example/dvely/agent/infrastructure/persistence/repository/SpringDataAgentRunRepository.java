package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAgentRunRepository extends JpaRepository<AgentRunEntity, String> {

    Optional<AgentRunEntity> findByTaskIdAndOwnerUserId(String taskId, Long ownerUserId);

    // Review follow-up (BLOCKING-3): backs TaskStore#requireWaitingResultApproval — acquires and
    // holds a row lock (SELECT ... FOR UPDATE) for the rest of the caller's transaction. Design
    // ADR-Y1 (#55) reuses this exact query as TaskStore#lockTask, the task-row mutex every
    // task-bound approval decision (approve/reject/RESULT/sweep) must acquire first.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRunEntity run where run.taskId = :taskId")
    Optional<AgentRunEntity> findByTaskIdForUpdate(@Param("taskId") String taskId);

    // LO-2 (design §2.1): taskId is a tiebreaker appended to the existing (nextRunAt, createdAt)
    // ordering so two worker instances racing this same candidate query against rows with
    // identical timestamps still agree on one total order across their per-row conditional
    // UPDATEs below — closing the (today theoretical, at claim's current batch size of 2) opposite-
    // order deadlock window the design's §2 analysis calls out.
    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status in :statuses
              and run.nextRunAt <= :now
            order by run.nextRunAt asc, run.createdAt asc, run.taskId asc
            """)
    List<String> findRunnableTaskIds(
            @Param("statuses") List<String> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.status = :runningStatus,
                   run.leaseOwner = :workerId,
                   run.leaseUntil = :leaseUntil,
                   run.nextRunAt = null
             where run.taskId = :taskId
               and run.status in :runnableStatuses
            """)
    int claim(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("runningStatus") String runningStatus,
            @Param("runnableStatuses") List<String> runnableStatuses
    );

    // ADR-Y3 (#55): hands a claimed-but-rejected task back to QUEUED. Deliberately does not touch
    // `attempt` (executor pool saturation is backpressure, not a failed run) and is guarded by
    // `lease_owner = :workerId` so only the worker instance that actually holds this claim can
    // release it — mirrors `claim`'s own conditional-UPDATE / affected-row-count pattern.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.status = :queuedStatus,
                   run.nextRunAt = :nextRunAt,
                   run.leaseOwner = null,
                   run.leaseUntil = null
             where run.taskId = :taskId
               and run.status = :runningStatus
               and run.leaseOwner = :workerId
            """)
    int releaseClaim(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("runningStatus") String runningStatus,
            @Param("queuedStatus") String queuedStatus
    );

    // ADR-Y5 (#55): scalar candidate IDs only, taskId-ascending (LO-2) — the actual recovery
    // decision happens in the per-task conditional UPDATEs below (failExhaustedLease/recoverLease),
    // which is what makes concurrent recovery attempts against the same row safe (closes audit G7's
    // double-recovery/double attempt-increment under multi-instance deployment).
    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status = :status
              and run.leaseUntil < :before
            order by run.taskId asc
            """)
    List<String> findExpiredLeaseTaskIds(
            @Param("status") String status,
            @Param("before") LocalDateTime before
    );

    // ADR-Y5: attempt-exhausted half of expired-lease recovery — only matches when the row is
    // still RUNNING with an actually-expired lease AND attempt has already reached the cap, so a
    // racing recoverLease (or a heartbeat that renewed the lease first) simply finds zero rows.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.status = :failedStatus,
                   run.error = :error,
                   run.failureLog = null,
                   run.suggestedFix = :suggestedFix,
                   run.question = null,
                   run.inputValue = null,
                   run.nextRunAt = null,
                   run.leaseOwner = null,
                   run.leaseUntil = null
             where run.taskId = :taskId
               and run.status = :runningStatus
               and run.leaseUntil < :now
               and run.attempt >= run.maxAttempts
            """)
    int failExhaustedLease(
            @Param("taskId") String taskId,
            @Param("runningStatus") String runningStatus,
            @Param("now") LocalDateTime now,
            @Param("failedStatus") String failedStatus,
            @Param("error") String error,
            @Param("suggestedFix") String suggestedFix
    );

    // ADR-Y5: retry-budget-remaining half of expired-lease recovery. `attempt = attempt + 1` is
    // computed SQL-side (not passed in from a client-side read) so this UPDATE's own row lock is
    // what serializes it against a concurrent attempt at the same row, not a stale attempt value
    // read before the lock was taken.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.status = :retryWaitStatus,
                   run.attempt = run.attempt + 1,
                   run.nextRunAt = :now,
                   run.leaseOwner = null,
                   run.leaseUntil = null
             where run.taskId = :taskId
               and run.status = :runningStatus
               and run.leaseUntil < :now
               and run.attempt < run.maxAttempts
            """)
    int recoverLease(
            @Param("taskId") String taskId,
            @Param("runningStatus") String runningStatus,
            @Param("now") LocalDateTime now,
            @Param("retryWaitStatus") String retryWaitStatus
    );

    // ADR-Y4 (#55): renewal scoped to the caller's AgentExecutionRegistry snapshot (`taskIds`) in
    // addition to the pre-existing owner+status filter — a task this worker claimed but the
    // executor rejected must never have its lease kept alive here (that was exactly the D2/G1
    // zombie bug: heartbeat renewing every RUNNING row unconditionally).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.leaseUntil = :leaseUntil
             where run.status = :runningStatus
               and run.leaseOwner = :workerId
               and run.taskId in :taskIds
            """)
    int renewWorkerLeases(
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("runningStatus") String runningStatus,
            @Param("taskIds") Collection<String> taskIds
    );

    // ADR-Y2 (#55): candidate scan for the stuck-approval sweep — unlocked scalar (real
    // re-verification happens under the task lock in
    // AgentOrchestrator#recoverStuckApprovedTask). The `updatedAt` grace period trades a little
    // recovery latency for fewer pointless lock waits against a task whose approve() is still in
    // flight; see TaskStore#STUCK_APPROVAL_GRACE.
    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status = :status
              and run.updatedAt < :before
            """)
    List<String> findStuckWaitingApprovalTaskIds(
            @Param("status") String status,
            @Param("before") LocalDateTime before
    );

    // 방치된 승인 대기 태스크 후보. 위 스윕과 조건이 정반대다 — 저쪽은 "승인이 전부 APPROVED
    // 인데 안 돌아간" 것을 구조하고, 이쪽은 "아무도 결정하지 않은 채 TTL 을 넘긴" 것을 닫는다.
    // 마찬가지로 비잠금 스칼라 읽기이고, 실제 재확인은 AgentOrchestrator#abandonStaleApprovalTask
    // 가 태스크 행 잠금 아래에서 한다.
    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status in :statuses
              and run.updatedAt < :before
            order by run.updatedAt asc
            """)
    List<String> findAbandonedApprovalTaskIds(
            @Param("statuses") List<String> statuses,
            @Param("before") LocalDateTime before
    );

    // 시작되지 않은 채 방치된 PENDING 태스크 후보. ChatCommandService 의 비동기 Decision 은
    // createPending 으로 PENDING 태스크를 먼저 커밋한 뒤 백그라운드에서 계획을 확정한다 —
    // 그 사이에 프로세스가 죽으면(배포 재기동 등) 태스크가 계획 없이 PENDING 으로 남고, 워커는
    // 이 상태를 집지 않아 스스로 빠져나올 길이 없다. createdAt 기준으로 잡는 이유는 PENDING 은
    // 생성 후 손대지 않는 상태라 createdAt 이 "언제부터 이러고 있었는지"를 그대로 나타내기 때문.
    // grace 는 한 번의 Decision 최대 소요(#238 read timeout 180s × 재시도)보다 넉넉히 커서 살아
    // 있는 Decision 을 잘못 잡지 않는다. 비잠금 스칼라 읽기이고, 재확인은 태스크 행 잠금 아래
    // AgentOrchestrator#failStalePendingTask 가 한다.
    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status = :status
              and run.createdAt < :before
            order by run.createdAt asc
            """)
    List<String> findStalePendingTaskIds(
            @Param("status") String status,
            @Param("before") LocalDateTime before
    );
}
