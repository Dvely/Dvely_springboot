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

    // CONNECTED 인데 httpsEnforced 가 아직 false 인 것 — 인증서 warming 후 뱃지를 채우려 재검증할 대상(#6).
    List<DomainBindingEntity> findByStatusAndHttpsEnforcedFalseOrderByCreatedAtAsc(String status, Pageable pageable);

    // IgnoreCase 가 없는 것은 실수가 아니다(#338). IgnoreCase 는 upper(domain_name)=upper(?) 를
    // 만들어 uk_domains_domain_name 을 통째로 무시하게 했다(EXPLAIN: type=index, 2만 행 스캔).
    // domain_name 의 컬레이션이 utf8mb4_unicode_ci 라 대소문자 무시는 IgnoreCase 없이도 그대로다
    // — IgnoreCase 는 동작을 더해주지 않으면서 UK 만 죽이고 있었다(같은 EXPLAIN: type=const, 1 행).
    boolean existsByHostname(String hostname);

    // hosting_target 는 String 컬럼. 위와 같은 이유로 IgnoreCase 를 쓰지 않는다.
    boolean existsByHostnameAndHostingTarget(String hostname, String hostingTarget);

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

    /** 현재 바인딩이 참조하는 CloudFront 배포 id 전부(null 제외). 고아 스윕의 활성 집합 — 이건 절대 안 지운다. */
    @Query("select e.cloudfrontDistributionId from DomainBindingEntity e where e.cloudfrontDistributionId is not null")
    List<String> findAllCloudfrontDistributionIds();
}
