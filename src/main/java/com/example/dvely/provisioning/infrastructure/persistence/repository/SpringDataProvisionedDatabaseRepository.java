package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseListView;
import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedDatabaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SpringDataProvisionedDatabaseRepository
        extends JpaRepository<ProvisionedDatabaseEntity, Long> {

    List<ProvisionedDatabaseEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    java.util.Optional<ProvisionedDatabaseEntity> findByApprovalId(Long approvalId);

    // 목록에서 EXPIRED 는 DB 단에서 제외한다(전 행 로드 후 메모리 필터를 피한다).
    List<ProvisionedDatabaseEntity> findByProjectIdAndStatusNotOrderByCreatedAtDesc(
            Long projectId, String status);

    // U6 6-5: 목록은 password 를 읽지 않는다. @Convert(AesEncryptor) 가 붙은 MEDIUMTEXT 라 엔티티로
    // 읽으면 행마다 복호화가 도는데, 조회 응답에는 비밀번호가 계약상 실리지 않는다.
    // EXPIRED 제외는 그대로 DB 단에서 한다(프리뷰 30분 TTL 이라 하루면 수십 개가 쌓인다).
    @Query("""
            select new com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseListView(
                       e.id, e.projectId, e.method, e.engine, e.origin, e.status, e.host, e.port,
                       e.databaseName, e.username, e.expiresAt, e.failureCode, e.errorMessage,
                       e.createdAt, e.updatedAt)
            from ProvisionedDatabaseEntity e
            where e.projectId = :projectId
              and e.status <> :excludedStatus
            order by e.createdAt desc, e.id desc
            """)
    List<ProvisionedDatabaseListView> findActiveListViews(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("excludedStatus") String excludedStatus);


    // 만료 회수의 원자적 클레임. READY 인 행만 EXPIRED 로 넘긴다 — 진 워커/인스턴스 하나만 1을
    // 돌려받아 실제 리소스 정리로 진행한다. 이래서 같은 DB 를 두 번 deprovision 하지 않는다.
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedDatabaseEntity e set e.status = 'EXPIRED', e.updatedAt = :now"
            + " where e.id = :id and e.status = 'READY'")
    int claimExpired(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("now") LocalDateTime now);

    // 부트 타임아웃 처리 권한을 status-CAS 로 claim(PROVISIONING→FAILED). 진 인스턴스 하나만 1을 받아
    // DB 인스턴스를 teardown 한다 — 다중 인스턴스에서 같은 DB 를 두 번 정리하지 않게(서버 워커와 동형).
    @Modifying(clearAutomatically = true)
    @Query("update ProvisionedDatabaseEntity e set e.status = 'FAILED', e.updatedAt = :now"
            + " where e.id = :id and e.status = 'PROVISIONING'")
    int claimBootTimeout(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("now") LocalDateTime now);

    // status 는 문자열 컬럼. READY 이면서 expiresAt 이 지난 것 — 만료 회수 대상.
    List<ProvisionedDatabaseEntity> findByStatusAndExpiresAtBefore(
            String status, LocalDateTime now, Pageable pageable);

    List<ProvisionedDatabaseEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
