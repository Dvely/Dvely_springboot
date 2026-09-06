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

    /**
     * 헬스 결과만 targeted 로 기록한다(전체-엔티티 저장 대신). 다중 인스턴스에서 각자 헬스체크하고 이 UPDATE
     * 로 써도 lost-update 가 없다 — healthy·lastHealthCheckAt 만 건드리므로 교체 워커의 EIP·supersedes 저장과
     * 충돌하지 않고, 겹쳐 써도 같은 값이라 무해하다. RUNNING 을 벗어난 서버(종료됨 등)면 no-op.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.healthy = :healthy, e.lastHealthCheckAt = :now"
            + " where e.id = :id and e.status = 'RUNNING'")
    int updateHealth(@Param("id") Long id, @Param("healthy") boolean healthy, @Param("now") LocalDateTime now);

    /**
     * 자동복구 권한을 원자적으로 claim(recovery_attempted_at: null→now). 진 인스턴스 하나만 1을 받아 재시작한다
     * — 다중 인스턴스에서 같은 앱을 두 번 재시작하지 않게. 전체-엔티티 저장을 안 하므로 이 값이 clobber 되지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.recoveryAttemptedAt = :now"
            + " where e.id = :id and e.recoveryAttemptedAt is null and e.status = 'RUNNING'")
    int claimRecovery(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 앱이 회복되면 이번 에피소드의 복구 표시를 모두 지운다 — 복구 시도 시각과 "실패 보고" 표시를 함께
     * 초기화해 다음 무응답 에피소드에 다시 복구·보고할 수 있게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.recoveryAttemptedAt = null,"
            + " e.recoveryOutcomeReportedAt = null where e.id = :id")
    int clearRecoveryAttempt(@Param("id") Long id);

    /**
     * "복구 실패(재시작해도 여전히 무응답)" 감사 이벤트를 에피소드당 1회만 남기도록 원자적으로 claim 한다.
     * 재시작을 시도했고(recovery_attempted_at NOT NULL) 정착 유예가 지났는데도(attempted_at ≤ :cutoff) 아직
     * 보고하지 않았고(reported_at NULL) 여전히 RUNNING·무응답(healthy=false)이면 진 인스턴스 하나만 1을 받는다.
     * 정착 유예(cutoff)는 재시작 앱이 뜰 시간을 줘 조기 오탐을 막는다. healthy 가 다시 true 로 바뀌었으면(회복)
     * 매치되지 않아 실패로 오인하지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedServerEntity e set e.recoveryOutcomeReportedAt = :now"
            + " where e.id = :id and e.status = 'RUNNING' and e.healthy = false"
            + " and e.recoveryAttemptedAt is not null and e.recoveryAttemptedAt <= :cutoff"
            + " and e.recoveryOutcomeReportedAt is null")
    int claimRecoveryOutcomeReport(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);

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
