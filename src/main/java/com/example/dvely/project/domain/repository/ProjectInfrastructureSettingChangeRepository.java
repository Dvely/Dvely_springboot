package com.example.dvely.project.domain.repository;

import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import java.util.List;
import java.util.Optional;

public interface ProjectInfrastructureSettingChangeRepository {

    ProjectInfrastructureSettingChange save(ProjectInfrastructureSettingChange change);

    Optional<ProjectInfrastructureSettingChange> findByApprovalId(Long approvalId);

    /** Used by the PUT PENDING-guard (design D8) — expected to return 0 or 1 rows in practice. */
    List<ProjectInfrastructureSettingChange> findByProjectIdAndStatusOrderByIdAsc(
            Long projectId, InfrastructureChangeStatus status
    );

    /** Most recent first (created_at desc, change_id desc as a tiebreaker), capped at {@code limit}. */
    List<ProjectInfrastructureSettingChange> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, int limit);
}
