package com.example.dvely.deployment.domain.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.project.domain.value.DeployStatus;
import java.util.List;
import java.util.Optional;

public interface DeploymentHistoryRepository {

    DeploymentHistory save(DeploymentHistory history);

    Optional<DeploymentHistory> findById(Long id);

    List<DeploymentHistory> findByProjectIdOrderByTriggeredAtDesc(Long projectId);

    Optional<DeploymentHistory> findLatestInProgressByProjectId(Long projectId);

    List<DeploymentHistory> findByProjectIdAndStatus(Long projectId, DeployStatus status);
}
