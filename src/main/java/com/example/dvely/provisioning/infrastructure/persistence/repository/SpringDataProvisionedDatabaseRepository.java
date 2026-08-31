package com.example.dvely.provisioning.infrastructure.persistence.repository;

import com.example.dvely.provisioning.infrastructure.persistence.entity.ProvisionedDatabaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProvisionedDatabaseRepository
        extends JpaRepository<ProvisionedDatabaseEntity, Long> {

    List<ProvisionedDatabaseEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    // status 는 문자열 컬럼. READY 이면서 expiresAt 이 지난 것 — 만료 회수 대상.
    List<ProvisionedDatabaseEntity> findByStatusAndExpiresAtBefore(
            String status, LocalDateTime now, Pageable pageable);

    List<ProvisionedDatabaseEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
