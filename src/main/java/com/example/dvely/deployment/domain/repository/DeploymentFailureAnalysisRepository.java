package com.example.dvely.deployment.domain.repository;

import com.example.dvely.deployment.domain.model.DeploymentFailureAnalysis;
import java.util.Optional;

public interface DeploymentFailureAnalysisRepository {

    DeploymentFailureAnalysis save(DeploymentFailureAnalysis analysis);

    Optional<DeploymentFailureAnalysis> findByHistoryId(Long historyId);
}
