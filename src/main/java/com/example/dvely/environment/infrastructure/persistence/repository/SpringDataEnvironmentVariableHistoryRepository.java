package com.example.dvely.environment.infrastructure.persistence.repository;

import com.example.dvely.environment.infrastructure.persistence.entity.EnvironmentVariableHistoryEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataEnvironmentVariableHistoryRepository
        extends JpaRepository<EnvironmentVariableHistoryEntity, Long> {

    // The trailing Pageable only supplies the offset/limit window (PageRequest.of(0, limit) from
    // the adapter) — the ORDER BY itself comes from the derived method name, matching the
    // "created_at desc, id desc" ordering the domain repository contract requires.
    List<EnvironmentVariableHistoryEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);
}
