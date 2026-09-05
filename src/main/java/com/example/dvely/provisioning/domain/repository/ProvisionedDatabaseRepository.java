package com.example.dvely.provisioning.domain.repository;

import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProvisionedDatabaseRepository {

    ProvisionedDatabase save(ProvisionedDatabase database);

    Optional<ProvisionedDatabase> findById(Long id);

    /** 승인 대상(RDS)을 그 승인 ID 로 찾는다. 승인 핸들러가 실행 시 대상 행을 집을 때 쓴다. */
    Optional<ProvisionedDatabase> findByApprovalId(Long approvalId);

    List<ProvisionedDatabase> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 목록용 — EXPIRED 를 뺀 활성 자원만. DB 단에서 거른다. */
    List<ProvisionedDatabase> findActiveByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 만료 회수의 원자적 클레임. READY 인 행만 EXPIRED 로 넘기고, 성공하면 true.
     * 이 클레임에 성공한 워커만 실제 리소스 정리로 진행한다 — 이중 deprovision 방지.
     */
    boolean claimForExpiry(Long id, LocalDateTime now);

    /**
     * 부트 타임아웃 처리 권한을 status-CAS 로 claim(PROVISIONING→FAILED). 진 인스턴스 하나만 true —
     * 그것만 DB 인스턴스를 teardown 한다(다중 인스턴스에서 같은 DB 를 두 번 정리 방지).
     */
    boolean claimBootTimeout(Long id);

    /**
     * 만료 회수 대상 — expiresAt 이 지났는데 아직 READY 인 LOCAL 자원. 워커가 EXPIRED 로 넘긴다.
     * "READY 인데 실제로는 없는" 상태를 그 주기 안에 정리하기 위한 것.
     */
    List<ProvisionedDatabase> findExpirable(LocalDateTime now, int limit);

    /** QUEUED(PENDING) 상태로 워커가 집을 대상. RDS 단계에서 확장, LOCAL 은 즉시 처리라 옵션. */
    List<ProvisionedDatabase> findByStatus(ProvisionStatus status, int limit);
}
