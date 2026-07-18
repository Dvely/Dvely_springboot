package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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
    // holds a row lock (SELECT ... FOR UPDATE) for the rest of the caller's transaction, so the
    // precondition it verifies (task is still WAITING_RESULT_APPROVAL) cannot change out from
    // under the caller before the caller performs its own irreversible external side effect.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRunEntity run where run.taskId = :taskId")
    Optional<AgentRunEntity> findByTaskIdForUpdate(@Param("taskId") String taskId);

    @Query("""
            select run.taskId
            from AgentRunEntity run
            where run.status in :statuses
              and run.nextRunAt <= :now
            order by run.nextRunAt asc, run.createdAt asc
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

    List<AgentRunEntity> findByStatusAndLeaseUntilBefore(String status, LocalDateTime leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunEntity run
               set run.leaseUntil = :leaseUntil
             where run.status = :runningStatus
               and run.leaseOwner = :workerId
            """)
    int renewWorkerLeases(
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("runningStatus") String runningStatus
    );
}
