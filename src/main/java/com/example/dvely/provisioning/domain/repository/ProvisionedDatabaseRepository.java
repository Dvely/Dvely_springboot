package com.example.dvely.provisioning.domain.repository;

import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProvisionedDatabaseRepository {

    ProvisionedDatabase save(ProvisionedDatabase database);

    Optional<ProvisionedDatabase> findById(Long id);

    List<ProvisionedDatabase> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 만료 회수 대상 — expiresAt 이 지났는데 아직 READY 인 LOCAL 자원. 워커가 EXPIRED 로 넘긴다.
     * "READY 인데 실제로는 없는" 상태를 그 주기 안에 정리하기 위한 것.
     */
    List<ProvisionedDatabase> findExpirable(LocalDateTime now, int limit);

    /** QUEUED(PENDING) 상태로 워커가 집을 대상. RDS 단계에서 확장, LOCAL 은 즉시 처리라 옵션. */
    List<ProvisionedDatabase> findByStatus(ProvisionStatus status, int limit);
}
