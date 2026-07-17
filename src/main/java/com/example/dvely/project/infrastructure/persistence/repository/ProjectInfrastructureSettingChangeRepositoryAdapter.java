package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.ProjectInfrastructureSettingChange;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingChangeRepository;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectInfrastructureSettingChangeEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectInfrastructureSettingChangeRepositoryAdapter
        implements ProjectInfrastructureSettingChangeRepository {

    private final SpringDataProjectInfrastructureSettingChangeRepository springDataRepository;

    // New rows (id == null) are created once by the PUT flow; existing rows are only ever
    // touched again by markApplied()/markRejected() (id present) — same insert-vs-update split
    // as ApprovalRepositoryAdapter#save, appropriate here because a change row's non-status
    // fields never change after creation (see entity's updateFrom doc).
    @Override
    public ProjectInfrastructureSettingChange save(ProjectInfrastructureSettingChange change) {
        if (change.getId() == null) {
            return springDataRepository.save(ProjectInfrastructureSettingChangeEntity.from(change)).toDomain();
        }
        ProjectInfrastructureSettingChangeEntity entity = springDataRepository.findById(change.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "인프라 설정 변경 이력을 찾을 수 없습니다. changeId=" + change.getId()));
        entity.updateFrom(change);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<ProjectInfrastructureSettingChange> findByApprovalId(Long approvalId) {
        return springDataRepository.findByApprovalId(approvalId)
                .map(ProjectInfrastructureSettingChangeEntity::toDomain);
    }

    @Override
    public List<ProjectInfrastructureSettingChange> findByProjectIdAndStatusOrderByIdAsc(
            Long projectId, InfrastructureChangeStatus status
    ) {
        return springDataRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, status.name())
                .stream()
                .map(ProjectInfrastructureSettingChangeEntity::toDomain)
                .toList();
    }

    @Override
    public List<ProjectInfrastructureSettingChange> findByProjectIdOrderByCreatedAtDescIdDesc(
            Long projectId, int limit
    ) {
        return springDataRepository
                .findByProjectIdOrderByCreatedAtDescIdDesc(projectId, PageRequest.of(0, limit))
                .stream()
                .map(ProjectInfrastructureSettingChangeEntity::toDomain)
                .toList();
    }
}
