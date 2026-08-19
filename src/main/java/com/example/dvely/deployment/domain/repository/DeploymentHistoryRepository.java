package com.example.dvely.deployment.domain.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.project.domain.value.DeployStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeploymentHistoryRepository {

    DeploymentHistory save(DeploymentHistory history);

    Optional<DeploymentHistory> findById(Long id);

    List<DeploymentHistory> findByProjectIdOrderByTriggeredAtDesc(Long projectId);

    Optional<DeploymentHistory> findLatestByProjectId(Long projectId);

    List<DeploymentHistory> findByProjectIdAndStatus(Long projectId, DeployStatus status);

    Optional<DeploymentHistory> findByWorkflowRunId(Long workflowRunId);

    Optional<DeploymentHistory> findByCorrelationId(String correlationId);

    /**
     * 워크플로를 띄워놓고 결과 웹훅을 못 받아 IN_PROGRESS 에 멈춘 이력.
     *
     * 리스가 없다는 것(leaseUntil is null)이 판별 기준이다. 워커가 붙들고 준비 중인 이력은
     * 리스를 갖고 있고 그 만료는 recoverExpiredLeases 가 따로 회수한다. markDispatched 는
     * 리스를 비우므로, 리스 없이 IN_PROGRESS 인 것은 GitHub 의 답만 기다리는 상태다.
     */
    List<DeploymentHistory> findDispatchedAwaitingOutcome(LocalDateTime updatedBefore, int limit);

    List<Long> claimPending(String workerId, int limit);

    void recoverExpiredLeases();

    void renewLeases(String workerId);
}
