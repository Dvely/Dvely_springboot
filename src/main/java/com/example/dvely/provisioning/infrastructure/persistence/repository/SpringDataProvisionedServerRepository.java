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

    /**
     * 재배포 교체 워커의 다중 인스턴스 리스 claim. RUNNING 이고 리스가 비었거나(NULL) 만료됐거나 내가 쥔
     * 것이면 내가 리스를 잡는다(1 반환) — 같은 소유자는 다음 틱에 이어받을 수 있다(교체가 여러 틱 걸림).
     * 이 UPDATE 는 lease 컬럼만 건드려 도메인 저장(EIP·supersedes 등)과 충돌하지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.leaseOwner = :owner, e.leaseUntil = :until"
            + " where e.id = :id and e.status = 'RUNNING'"
            + " and (e.leaseUntil is null or e.leaseUntil < :now or e.leaseOwner = :owner)")
    int claimForReplacement(@Param("id") Long id, @Param("owner") String owner,
            @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    /**
     * 부트 타임아웃 처리 권한을 status-CAS 로 claim(PROVISIONING→FAILED). 승자만 1 을 받아 인스턴스를
     * 종료하고 부트 로그를 뜬다 — 다중 인스턴스에서 두 곳이 같은 인스턴스를 종료·SSM 조회하지 않게.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.status = 'FAILED', e.updatedAt = :now"
            + " where e.id = :id and e.status = 'PROVISIONING'")
    int claimBootTimeout(@Param("id") Long id, @Param("now") LocalDateTime now);

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
