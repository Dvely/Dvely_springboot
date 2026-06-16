package com.example.dvely.project.domain.model;

import com.example.dvely.approval.domain.value.ApprovalType;

public class ProjectApprovalPolicy {

    private final Long projectId;
    private boolean changeApprovalRequired;
    private boolean deploymentApprovalRequired;
    private boolean domainApprovalRequired;
    private boolean infraApprovalRequired;

    public ProjectApprovalPolicy(Long projectId) {
        this(projectId, true, true, true, true);
    }

    public ProjectApprovalPolicy(Long projectId,
                                 boolean changeApprovalRequired,
                                 boolean deploymentApprovalRequired,
                                 boolean domainApprovalRequired,
                                 boolean infraApprovalRequired) {
        this.projectId = projectId;
        this.changeApprovalRequired = changeApprovalRequired;
        this.deploymentApprovalRequired = deploymentApprovalRequired;
        this.domainApprovalRequired = domainApprovalRequired;
        this.infraApprovalRequired = infraApprovalRequired;
    }

    public boolean requires(ApprovalType type) {
        return switch (type) {
            case CHANGE -> changeApprovalRequired;
            case DEPLOYMENT -> deploymentApprovalRequired;
            case DOMAIN_BINDING -> domainApprovalRequired;
            case INFRA_OPERATION -> infraApprovalRequired;
        };
    }

    public void update(boolean changeApprovalRequired,
                       boolean deploymentApprovalRequired,
                       boolean domainApprovalRequired,
                       boolean infraApprovalRequired) {
        this.changeApprovalRequired = changeApprovalRequired;
        this.deploymentApprovalRequired = deploymentApprovalRequired;
        this.domainApprovalRequired = domainApprovalRequired;
        this.infraApprovalRequired = infraApprovalRequired;
    }

    public Long getProjectId() { return projectId; }
    public boolean isChangeApprovalRequired() { return changeApprovalRequired; }
    public boolean isDeploymentApprovalRequired() { return deploymentApprovalRequired; }
    public boolean isDomainApprovalRequired() { return domainApprovalRequired; }
    public boolean isInfraApprovalRequired() { return infraApprovalRequired; }
}
