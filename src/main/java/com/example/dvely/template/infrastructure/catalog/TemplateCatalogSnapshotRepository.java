package com.example.dvely.template.infrastructure.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateCatalogSnapshotRepository
        extends JpaRepository<TemplateCatalogSnapshotEntity, Byte> {
}
