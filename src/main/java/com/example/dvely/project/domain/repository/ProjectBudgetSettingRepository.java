package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.ProjectBudgetSetting;
import java.util.Optional;

public interface ProjectBudgetSettingRepository {

    /** Upsert by projectId (the primary key) — see the adapter for the findById/updateFrom merge. */
    ProjectBudgetSetting save(ProjectBudgetSetting setting);

    Optional<ProjectBudgetSetting> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
