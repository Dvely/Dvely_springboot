package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import java.util.Optional;

public interface ProjectInfrastructureSettingRepository {

    /** Upsert by projectId (the primary key) — see the adapter for the findById/updateFrom merge. */
    ProjectInfrastructureSetting save(ProjectInfrastructureSetting setting);

    Optional<ProjectInfrastructureSetting> findByProjectId(Long projectId);
}
