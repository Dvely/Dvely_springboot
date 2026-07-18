package com.example.dvely.project.application.service;

import com.example.dvely.project.application.result.ProjectChatSettingsResult;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectChatSettingsService {

    private final ProjectRepository projectRepository;
    private final ProjectApprovalPolicyRepository policyRepository;

    @Transactional(readOnly = true)
    public ProjectChatSettingsResult get(Long ownerUserId, Long projectId) {
        assertProjectOwner(ownerUserId, projectId);
        return toResult(resolvePolicy(projectId));
    }

    /**
     * {@code resultApprovalRequired} is nullable (design D4/§5.5) unlike the other four fields:
     * the RESULT policy shipped after this endpoint's {@code @NotNull}-on-all-fields contract was
     * already in use by the FE, so a {@code null} here means "leave the current value untouched"
     * rather than "set to false" — an older FE build that doesn't know about this field yet keeps
     * working (200, unchanged RESULT policy) instead of getting a validation error or silently
     * flipping RESULT approval off.
     */
    @Transactional
    public ProjectChatSettingsResult update(Long ownerUserId,
                                            Long projectId,
                                            boolean changeApprovalRequired,
                                            boolean deploymentApprovalRequired,
                                            boolean domainApprovalRequired,
                                            boolean infraApprovalRequired,
                                            Boolean resultApprovalRequired) {
        assertProjectOwner(ownerUserId, projectId);
        ProjectApprovalPolicy policy = resolvePolicy(projectId);
        policy.update(
                changeApprovalRequired,
                deploymentApprovalRequired,
                domainApprovalRequired,
                infraApprovalRequired,
                resultApprovalRequired == null ? policy.isResultApprovalRequired() : resultApprovalRequired
        );
        return toResult(policyRepository.save(policy));
    }

    private ProjectApprovalPolicy resolvePolicy(Long projectId) {
        return policyRepository.findByProjectId(projectId)
                .orElseGet(() -> new ProjectApprovalPolicy(projectId));
    }

    private void assertProjectOwner(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
    }

    private ProjectChatSettingsResult toResult(ProjectApprovalPolicy policy) {
        return new ProjectChatSettingsResult(
                policy.getProjectId(),
                policy.isChangeApprovalRequired(),
                policy.isDeploymentApprovalRequired(),
                policy.isDomainApprovalRequired(),
                policy.isInfraApprovalRequired(),
                policy.isResultApprovalRequired()
        );
    }
}
