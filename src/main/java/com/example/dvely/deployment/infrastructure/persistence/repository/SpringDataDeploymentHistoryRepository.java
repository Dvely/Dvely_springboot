package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.domain.repository.DeploymentHistoryListView;
import com.example.dvely.deployment.domain.repository.DeploymentVersionView;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataDeploymentHistoryRepository extends JpaRepository<DeploymentHistoryEntity, Long> {

    // U6 6-2 ①: 이력 목록. 응답에 나가는 11컬럼만 읽는다(엔티티는 28컬럼 + TEXT 2개).
    // triggeredAt 이 DATETIME(초) 이라 같은 초의 행끼리 순서가 비결정적이었다 — id 를 tiebreaker 로
    // 붙여 고정한다. 프로젝트 개요가 이 목록의 첫 건을 "최신 배포" 로 쓰는데, 그 값이 조회마다
    // 달라질 수 있었다(findFirstByProjectIdOrderByTriggeredAtDescIdDesc 와도 어긋났다).
    @Query("""
            select new com.example.dvely.deployment.domain.repository.DeploymentHistoryListView(
                       history.id, history.projectId, history.deployTargetType, history.versionLabel,
                       history.deployedUrl, history.status, history.failureCode, history.errorMessage,
                       history.triggeredAt, history.updatedAt, history.retriedFromHistoryId)
            from DeploymentHistoryEntity history
            where history.projectId = :projectId
            order by history.triggeredAt desc, history.id desc
            """)
    List<DeploymentHistoryListView> findHistoryListViews(@Param("projectId") Long projectId);

    // U6 6-2 ②: 버전 목록. version_label 이 비지 않은 행만 — 예전에는 전체를 읽어 메모리에서 걸렀다.
    @Query("""
            select new com.example.dvely.deployment.domain.repository.DeploymentVersionView(
                       history.id, history.versionLabel, history.commitSha, history.title,
                       history.status, history.deployedUrl, history.triggeredAt, history.mergedAt,
                       history.updatedAt)
            from DeploymentHistoryEntity history
            where history.projectId = :projectId
              and history.versionLabel is not null
              and trim(history.versionLabel) <> ''
            order by history.triggeredAt desc, history.id desc
            """)
    List<DeploymentVersionView> findLabeledVersionViews(@Param("projectId") Long projectId);

    // U6 6-2 ③: 배포 후보. ② 에 "LIVE 만" 이 더 붙는다. 두 조건 모두 SQL 에 있다.
    @Query("""
            select new com.example.dvely.deployment.domain.repository.DeploymentVersionView(
                       history.id, history.versionLabel, history.commitSha, history.title,
                       history.status, history.deployedUrl, history.triggeredAt, history.mergedAt,
                       history.updatedAt)
            from DeploymentHistoryEntity history
            where history.projectId = :projectId
              and history.status = :liveStatus
              and history.versionLabel is not null
              and trim(history.versionLabel) <> ''
            order by history.triggeredAt desc, history.id desc
            """)
    List<DeploymentVersionView> findLiveLabeledVersionViews(
            @Param("projectId") Long projectId, @Param("liveStatus") String liveStatus);

    // U6 6-2 ④: DomainBindingCommandService 가 쓰는 "가장 최근 LIVE 1건의 URL". 예전에는 전체 이력을
    // 엔티티로 읽어 첫 LIVE 하나만 꺼냈다. 값이 비어 있으면 다음 LIVE 로 넘어가지 <b>않는</b> 기존
    // 동작을 유지하려고 URL 의 공백 판정은 호출부에 남겨둔다 — 여기서 걸러내면 동작이 달라진다.
    @Query("""
            select history.deployedUrl
            from DeploymentHistoryEntity history
            where history.projectId = :projectId
              and history.status = :liveStatus
            order by history.triggeredAt desc, history.id desc
            """)
    List<String> findLiveDeployedUrls(
            @Param("projectId") Long projectId, @Param("liveStatus") String liveStatus, Pageable pageable);

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

    // #340 5-8: 갱신 범위를 호출자의 DeploymentExecutionRegistry 스냅샷(historyIds)으로 좁힌다.
    // 이 워커가 claim 했지만 실행기가 거부한 이력의 리스를 여기서 계속 살려두면
    // recoverExpiredLeases 가 그것을 영영 회수하지 못한다(AgentRunWorker 의 ADR-Y4 와 같은 이유).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeploymentHistoryEntity history
               set history.leaseUntil = :leaseUntil
             where history.status = :runningStatus
               and history.leaseOwner = :workerId
               and history.id in :historyIds
            """)
    int renewLeases(
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("runningStatus") String runningStatus,
            @Param("historyIds") Collection<Long> historyIds
    );
}
