package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAgentRunRepository extends JpaRepository<AgentRunEntity, String> {

    Optional<AgentRunEntity> findByTaskIdAndOwnerUserId(String taskId, Long ownerUserId);

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
