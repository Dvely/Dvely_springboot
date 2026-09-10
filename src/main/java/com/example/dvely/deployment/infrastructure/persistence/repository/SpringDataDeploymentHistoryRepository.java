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

    // #340 5-2: claim 된 이력을 실행기에 넘기지 못했을 때 PENDING 으로 되돌린다. claim 이 올린
    // attempt 를 그대로 되돌리는 이유는, 실행기 포화가 이 배포의 실패가 아니라 시스템 부하로 인한
    // 배압이기 때문이다 — 재시도 예산을 여기서 쓰면 사용자는 아무 잘못 없이 재시도 횟수를 잃는다
    // (AgentRunWorker 의 ADR-Y3 releaseClaim 이 attempt 를 건드리지 않는 것과 같은 판단).
    // WHERE 는 claim 과 같은 조건부 UPDATE 모양(id + status + lease_owner)이라, 이 claim 을 실제로
    // 쥔 워커만 되돌릴 수 있고 동시에 도는 recoverExpiredLeases 와 이중 전이를 만들지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentHistoryEntity history
               set history.status = :pendingStatus,
                   history.attempt = history.attempt - 1,
                   history.nextRunAt = :nextRunAt,
                   history.leaseOwner = null,
                   history.leaseUntil = null
             where history.id = :historyId
               and history.status = :runningStatus
               and history.leaseOwner = :workerId
            """)
    int releaseClaim(
            @Param("historyId") Long historyId,
            @Param("workerId") String workerId,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("runningStatus") String runningStatus,
            @Param("pendingStatus") String pendingStatus
    );

    List<DeploymentHistoryEntity> findByStatusAndLeaseUntilBefore(String status, LocalDateTime now);

    List<DeploymentHistoryEntity> findByStatusAndLeaseUntilIsNullAndUpdatedAtBefore(
            String status, LocalDateTime updatedBefore, Pageable pageable);

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
