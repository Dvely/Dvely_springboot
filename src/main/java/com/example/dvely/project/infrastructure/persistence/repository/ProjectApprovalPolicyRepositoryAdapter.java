package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectApprovalPolicyEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectApprovalPolicyRepositoryAdapter implements ProjectApprovalPolicyRepository {

    private final SpringDataProjectApprovalPolicyRepository springDataRepository;

    @Override
    public Optional<ProjectApprovalPolicy> findByProjectId(Long projectId) {
        return springDataRepository.findById(projectId).map(ProjectApprovalPolicyEntity::toDomain);
    }

    @Override
    public ProjectApprovalPolicy save(ProjectApprovalPolicy policy) {
        ProjectApprovalPolicyEntity entity = springDataRepository.findById(policy.getProjectId())
                .orElseGet(() -> ProjectApprovalPolicyEntity.from(policy));
        entity.updateFrom(policy);
        return springDataRepository.save(entity).toDomain();
    }
}
