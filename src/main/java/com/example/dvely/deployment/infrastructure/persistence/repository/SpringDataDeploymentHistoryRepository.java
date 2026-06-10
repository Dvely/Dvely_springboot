package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataDeploymentHistoryRepository extends JpaRepository<DeploymentHistoryEntity, Long> {

    List<DeploymentHistoryEntity> findByProjectIdOrderByTriggeredAtDesc(Long projectId);

    Optional<DeploymentHistoryEntity> findFirstByProjectIdOrderByTriggeredAtDescIdDesc(Long projectId);

    List<DeploymentHistoryEntity> findByProjectIdAndStatus(Long projectId, String status);

    Optional<DeploymentHistoryEntity> findByWorkflowRunId(Long workflowRunId);

    Optional<DeploymentHistoryEntity> findByCorrelationId(String correlationId);

    @Query("""
            select history.id
            from DeploymentHistoryEntity history
            where history.status = :pendingStatus
              and history.nextRunAt <= :now
            order by history.nextRunAt asc, history.triggeredAt asc
            """)
    List<Long> findRunnableIds(
            @Param("pendingStatus") String pendingStatus,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentHistoryEntity history
               set history.status = :runningStatus,
                   history.attempt = history.attempt + 1,
                   history.leaseOwner = :workerId,
                   history.leaseUntil = :leaseUntil,
                   history.nextRunAt = null
             where history.id = :historyId
               and history.status = :pendingStatus
            """)
    int claim(
            @Param("historyId") Long historyId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    List<DeploymentHistoryEntity> findByStatusAndLeaseUntilBefore(String status, LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentHistoryEntity history
               set history.leaseUntil = :leaseUntil
             where history.status = :runningStatus
               and history.leaseOwner = :workerId
            """)
    int renewLeases(
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("runningStatus") String runningStatus
    );
}
