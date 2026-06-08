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

    @Transactional
    public ProjectChatSettingsResult update(Long ownerUserId,
                                            Long projectId,
                                            boolean changeApprovalRequired,
                                            boolean deploymentApprovalRequired,
                                            boolean domainApprovalRequired,
                                            boolean infraApprovalRequired) {
        assertProjectOwner(ownerUserId, projectId);
        ProjectApprovalPolicy policy = resolvePolicy(projectId);
        policy.update(
                changeApprovalRequired,
                deploymentApprovalRequired,
                domainApprovalRequired,
                infraApprovalRequired
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
                policy.isInfraApprovalRequired()
        );
    }
}
