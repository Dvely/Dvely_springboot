package com.example.dvely.domainbinding.infrastructure.persistence.repository;

import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataDomainBindingRepository extends JpaRepository<DomainBindingEntity, Long> {

    List<DomainBindingEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    // status 컬럼은 enum 이 아니라 String 이다(DomainBindingEntity:46).
    List<DomainBindingEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    boolean existsByHostnameIgnoreCase(String hostname);

    // hosting_target 는 String 컬럼.
    boolean existsByHostnameIgnoreCaseAndHostingTarget(String hostname, String hostingTarget);

    /**
     * S3 CDN 프로비저닝 워커의 다중 인스턴스 리스 claim. PROVISIONING 이고 리스가 비었거나 만료됐거나 내가
     * 쥔 것이면 내가 잡는다(1 반환) — 그때만 CloudFront 배포·ACM 인증서 생성을 진행해 중복 자원 생성을 막는다.
     * lease 컬럼만 건드려 도메인 저장(자원 id·상태 등)과 충돌하지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update DomainBindingEntity e set e.leaseOwner = :owner, e.leaseUntil = :until"
            + " where e.id = :id and e.status = 'PROVISIONING'"
            + " and (e.leaseUntil is null or e.leaseUntil < :now or e.leaseOwner = :owner)")
    int claimForCdnProvision(@Param("id") Long id, @Param("owner") String owner,
            @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);
}
