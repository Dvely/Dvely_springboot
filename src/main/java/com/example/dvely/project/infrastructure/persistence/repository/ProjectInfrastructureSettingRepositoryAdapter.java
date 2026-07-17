package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectInfrastructureSettingEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectInfrastructureSettingRepositoryAdapter implements ProjectInfrastructureSettingRepository {

    private final SpringDataProjectInfrastructureSettingRepository springDataRepository;

    // Upsert-by-merge (project_id is both PK and the natural key) — same shape as
    // ProjectCloudConnectionSettingRepositoryAdapter: find the existing row if any, then copy
    // the domain object's current values onto it, so JPA either INSERTs or UPDATEs correctly
    // without the caller having to know which case applies.
    @Override
    public ProjectInfrastructureSetting save(ProjectInfrastructureSetting setting) {
        ProjectInfrastructureSettingEntity entity = springDataRepository.findById(setting.getProjectId())
                .orElseGet(() -> ProjectInfrastructureSettingEntity.from(setting));
        entity.updateFrom(setting);
        // saveAndFlush (review F4, same root cause as U3 F2): a plain save() on an existing row
        // only schedules the UPDATE for the next flush, and @UpdateTimestamp is only populated
        // as part of that flush. The immediate-apply PUT path
        // (ProjectInfrastructureConfigurationService.update) re-reads this row in the same
        // transaction right after saving it to build the response — without forcing the flush
        // now, that re-read would return the *pre-update* updatedAt from the still-open
        // persistence context, showing a stale timestamp even though the write itself succeeded.
        return springDataRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<ProjectInfrastructureSetting> findByProjectId(Long projectId) {
        return springDataRepository.findById(projectId).map(ProjectInfrastructureSettingEntity::toDomain);
    }
}
