package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryListView;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkQueuedEvent;
import com.example.dvely.deployment.domain.repository.DeploymentVersionView;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentHistoryEntity;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class DeploymentHistoryRepositoryAdapter implements DeploymentHistoryRepository {

    private final SpringDataDeploymentHistoryRepository springDataRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public DeploymentHistory save(DeploymentHistory history) {
        DeploymentHistory saved = doSave(history);
        if (saved.getStatus() == DeployStatus.PENDING) {
            // #340 5-1: 워커가 집을 수 있는 상태로 저장됐다 — 커밋 뒤에 워커를 깨운다. 이 신호가
            // 없으면 유휴가 길어져 폴링 간격이 상한까지 늘어난 뒤 배포 버튼을 누른 사용자가 그
            // 상한만큼 기다린다.
            eventPublisher.publishEvent(new WorkQueuedEvent(WorkQueue.DEPLOYMENT_RUN));
        }
        return saved;
    }

    private DeploymentHistory doSave(DeploymentHistory history) {
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
    public List<DeploymentHistoryListView> findHistoryListViews(Long projectId) {
        return springDataRepository.findHistoryListViews(projectId);
    }

    @Override
    public List<DeploymentVersionView> findLabeledVersionViews(Long projectId) {
        return springDataRepository.findLabeledVersionViews(projectId);
    }

    @Override
    public List<DeploymentVersionView> findLiveLabeledVersionViews(Long projectId) {
        return springDataRepository.findLiveLabeledVersionViews(projectId, DeployStatus.LIVE.name());
    }

    @Override
    public Optional<String> findLatestLiveDeployedUrl(Long projectId) {
        // deployed_url 은 NULL 일 수 있다. 리스트에 null 원소가 담기므로 Stream#findFirst 를 쓰면
        // NPE 다 — 그래서 직접 꺼내 ofNullable 로 감싼다. "LIVE 가 없다" 와 "LIVE 인데 URL 이
        // 비었다" 를 여기서 구분하지 않는 것은 의도다(호출부가 예전처럼 공백까지 판정한다).
        List<String> urls = springDataRepository.findLiveDeployedUrls(
                projectId, DeployStatus.LIVE.name(), PageRequest.of(0, 1));
        return urls.isEmpty() ? Optional.empty() : Optional.ofNullable(urls.get(0));
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
    public List<DeploymentHistory> findDispatchedAwaitingOutcome(LocalDateTime updatedBefore, int limit) {
        return springDataRepository.findByStatusAndLeaseUntilIsNullAndUpdatedAtBefore(
                        DeployStatus.IN_PROGRESS.name(),
                        updatedBefore,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(DeploymentHistoryEntity::toDomain)
                .toList();
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
    public List<Long> recoverAndClaimPending(String workerId, int limit) {
        recoverExpiredLeases();
        return claimPending(workerId, limit);
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
    public boolean releaseClaim(Long historyId, String workerId, long backoffMillis) {
        return springDataRepository.releaseClaim(
                historyId,
                workerId,
                LocalDateTime.now().plus(Duration.ofMillis(backoffMillis)),
                DeployStatus.IN_PROGRESS.name(),
                DeployStatus.PENDING.name()
        ) == 1;
    }

    @Override
    @Transactional
    public void renewLeases(String workerId, Collection<Long> historyIds) {
        if (historyIds.isEmpty()) {
            return;
        }
        springDataRepository.renewLeases(
                workerId,
                LocalDateTime.now().plusMinutes(2),
                DeployStatus.IN_PROGRESS.name(),
                historyIds
        );
    }
}
