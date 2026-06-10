package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public Optional<DeploymentHistory> findLatestByProjectId(Long projectId) {
        return springDataRepository.findFirstByProjectIdOrderByTriggeredAtDescIdDesc(projectId)
                .map(DeploymentHistoryEntity::toDomain);
    }

    @Override
    public List<DeploymentHistory> findByProjectIdAndStatus(Long projectId, DeployStatus status) {
        return springDataRepository.findByProjectIdAndStatus(projectId, status.name())
                .stream().map(DeploymentHistoryEntity::toDomain).toList();
    }

    @Override
    public Optional<DeploymentHistory> findByWorkflowRunId(Long workflowRunId) {
        return springDataRepository.findByWorkflowRunId(workflowRunId)
                .map(DeploymentHistoryEntity::toDomain);
    }

    @Override
    public Optional<DeploymentHistory> findByCorrelationId(String correlationId) {
        return springDataRepository.findByCorrelationId(correlationId)
                .map(DeploymentHistoryEntity::toDomain);
    }

    @Override
    @Transactional
    public List<Long> claimPending(String workerId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(2);
        return springDataRepository.findRunnableIds(
                        DeployStatus.PENDING.name(),
                        now,
                        PageRequest.of(0, limit)
                )
                .stream()
                .filter(id -> springDataRepository.claim(
                        id,
                        workerId,
                        leaseUntil,
                        DeployStatus.PENDING.name(),
                        DeployStatus.IN_PROGRESS.name()
                ) == 1)
                .toList();
    }

    @Override
    @Transactional
    public void recoverExpiredLeases() {
        springDataRepository.findByStatusAndLeaseUntilBefore(
                        DeployStatus.IN_PROGRESS.name(),
                        LocalDateTime.now()
                )
                .forEach(entity -> {
                    DeploymentHistory history = entity.toDomain();
                    history.retry(
                            "worker lease가 만료되어 배포 준비를 다시 시도합니다.",
                            Duration.ZERO
                    );
                    entity.updateFrom(history);
                });
    }

    @Override
    @Transactional
    public void renewLeases(String workerId) {
        springDataRepository.renewLeases(
                workerId,
                LocalDateTime.now().plusMinutes(2),
                DeployStatus.IN_PROGRESS.name()
        );
    }
}
