package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.ProjectBudgetSetting;
import com.example.dvely.project.domain.repository.ProjectBudgetSettingRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectBudgetSettingEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectBudgetSettingRepositoryAdapter implements ProjectBudgetSettingRepository {

    private final SpringDataProjectBudgetSettingRepository springDataRepository;

    // Upsert-by-merge (project_id is both PK and the natural key) — same shape as
    // ProjectInfrastructureSettingRepositoryAdapter: find the existing row if any, then copy the
    // domain object's current values onto it, so JPA either INSERTs or UPDATEs correctly without
    // the caller having to know which case applies.
    @Override
    public ProjectBudgetSetting save(ProjectBudgetSetting setting) {
        ProjectBudgetSettingEntity entity = springDataRepository.findById(setting.getProjectId())
                .orElseGet(() -> ProjectBudgetSettingEntity.from(setting));
        entity.updateFrom(setting);
        // saveAndFlush (same reasoning as ProjectInfrastructureSettingRepositoryAdapter): the PUT
        // path re-reads this row's updatedAt within the same transaction to build its response,
        // and @UpdateTimestamp is only populated once the flush actually runs — a plain save()
        // would leave that re-read seeing the pre-update timestamp.
        return springDataRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<ProjectBudgetSetting> findByProjectId(Long projectId) {
        return springDataRepository.findById(projectId).map(ProjectBudgetSettingEntity::toDomain);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        springDataRepository.deleteById(projectId);
    }
}
