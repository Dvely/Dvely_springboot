package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedServerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataProvisionedServerRepository
        extends JpaRepository<ProvisionedServerEntity, Long> {

    Optional<ProvisionedServerEntity> findByApprovalId(Long approvalId);

    List<ProvisionedServerEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProvisionedServerEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /** 교체 대기: RUNNING 이 됐지만 아직 옛 서버(supersedes)를 정리하지 않은 것. 리플레이스 워커가 집는다. */
    List<ProvisionedServerEntity> findByStatusAndSupersedesServerIdIsNotNullOrderByCreatedAtAsc(
            String status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.status = 'BUILDING', e.updatedAt = :now"
            + " where e.id = :id and e.status = 'QUEUED'")
    int claimForBuild(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("select distinct e.cloudConnectionId from ProvisionedServerEntity e"
            + " where e.cloudConnectionId is not null")
    List<Long> findDistinctCloudConnectionIds();

    @Query("select count(e) from ProvisionedServerEntity e where e.cloudConnectionId = :connId"
            + " and e.status in ('QUEUED', 'BUILDING', 'PROVISIONING')")
    long countInFlightByCloudConnectionId(@Param("connId") Long connId);

    @Query("select e.elasticIpAllocationId from ProvisionedServerEntity e"
            + " where e.cloudConnectionId = :connId and e.status = :status"
            + " and e.elasticIpAllocationId is not null")
    List<String> findElasticIpAllocationIds(@Param("connId") Long connId, @Param("status") String status);
}
