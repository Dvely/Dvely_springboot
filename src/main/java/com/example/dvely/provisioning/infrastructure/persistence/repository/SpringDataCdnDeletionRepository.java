package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** CloudFront 배포 정리(리프) 큐. 리퍼 워커가 폴링해 Deployed 된 배포를 지운다. */
public interface SpringDataCdnDeletionRepository extends JpaRepository<CdnDeletionEntity, Long> {

    /**
     * 리퍼의 다중 인스턴스 리스 claim. 리스가 비었거나 만료됐거나 내가 쥔 것이면 내가 잡는다(1 반환) —
     * 그때만 이 배포를 disable·delete 한다(두 인스턴스가 같은 CloudFront 를 중복 호출하지 않게).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CdnDeletionEntity e set e.leaseOwner = :owner, e.leaseUntil = :until"
            + " where e.id = :id and (e.leaseUntil is null or e.leaseUntil < :now or e.leaseOwner = :owner)")
    int claimForReap(@Param("id") Long id, @Param("owner") String owner,
            @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    /** 실패 기록을 targeted 로만 한다(전체 저장 대신) — lease 컬럼을 덮어쓰지 않게. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CdnDeletionEntity e set e.lastError = :error where e.id = :id")
    int recordError(@Param("id") Long id, @Param("error") String error);
}
