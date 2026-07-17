package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentFailureAnalysisEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDeploymentFailureAnalysisRepository
        extends JpaRepository<DeploymentFailureAnalysisEntity, Long> {

    Optional<DeploymentFailureAnalysisEntity> findByHistoryId(Long historyId);
}
