package com.example.dvely.deployment.infrastructure.persistence.repository;

import com.example.dvely.deployment.domain.model.DeploymentFailureAnalysis;
import com.example.dvely.deployment.domain.repository.DeploymentFailureAnalysisRepository;
import com.example.dvely.deployment.infrastructure.persistence.entity.DeploymentFailureAnalysisEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeploymentFailureAnalysisRepositoryAdapter implements DeploymentFailureAnalysisRepository {

    private final SpringDataDeploymentFailureAnalysisRepository springDataRepository;

    @Override
    public DeploymentFailureAnalysis save(DeploymentFailureAnalysis analysis) {
        // saveAndFlush forces the INSERT (and its uk_deployment_failure_analyses_history check)
        // to happen synchronously here, not at transaction commit — otherwise a concurrent
        // duplicate-analysis race would raise DataIntegrityViolationException only after
        // DeploymentFailureAnalysisService#analyze() has already returned, where it can no
        // longer be caught and turned into a re-fetch-and-return (design §3.4). Same lesson as
        // environment.infrastructure.persistence.repository.EnvironmentVariableRepositoryAdapter.
        return springDataRepository.saveAndFlush(DeploymentFailureAnalysisEntity.from(analysis)).toDomain();
    }

    @Override
    public Optional<DeploymentFailureAnalysis> findByHistoryId(Long historyId) {
        return springDataRepository.findByHistoryId(historyId).map(DeploymentFailureAnalysisEntity::toDomain);
    }
}
