package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeploymentHistoryRepositoryAdapter implements DeploymentHistoryRepository {

    private final SpringDataDeploymentHistoryRepository springDataRepository;

    @Override
    public DeploymentHistory save(DeploymentHistory history) {
        if (history.getId() == null) {
            DeploymentHistoryEntity entity = springDataRepository.save(DeploymentHistoryEntity.from(history));
            return entity.toDomain();
        }
        DeploymentHistoryEntity entity = springDataRepository.findById(history.getId())
                .orElseThrow(() -> new IllegalStateException("배포 이력을 찾을 수 없습니다. id=" + history.getId()));
        entity.updateFrom(history);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<DeploymentHistory> findById(Long id) {
        return springDataRepository.findById(id).map(DeploymentHistoryEntity::toDomain);
    }

    @Override
    public List<DeploymentHistory> findByProjectIdOrderByTriggeredAtDesc(Long projectId) {
        return springDataRepository.findByProjectIdOrderByTriggeredAtDesc(projectId)
                .stream().map(DeploymentHistoryEntity::toDomain).toList();
    }

    @Override
    public Optional<DeploymentHistory> findLatestInProgressByProjectId(Long projectId) {
        return springDataRepository.findLatestInProgressByProjectId(projectId)
                .map(DeploymentHistoryEntity::toDomain);
    }

    @Override
    public List<DeploymentHistory> findByProjectIdAndStatus(Long projectId, DeployStatus status) {
        return springDataRepository.findByProjectIdAndStatus(projectId, status.name())
                .stream().map(DeploymentHistoryEntity::toDomain).toList();
    }
}
