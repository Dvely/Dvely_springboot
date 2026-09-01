package com.example.dvely.preview.infrastructure.persistence.repository;

import com.example.dvely.preview.infrastructure.persistence.entity.PreviewRuntimeConfigEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPreviewRuntimeConfigRepository
        extends JpaRepository<PreviewRuntimeConfigEntity, Long> {

    Optional<PreviewRuntimeConfigEntity> findByProjectId(Long projectId);
}
