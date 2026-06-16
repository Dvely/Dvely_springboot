package com.example.dvely.project.infrastructure.persistence.entity;

import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project_approval_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectApprovalPolicyEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "change_approval_required", nullable = false)
    private boolean changeApprovalRequired;

    @Column(name = "deployment_approval_required", nullable = false)
    private boolean deploymentApprovalRequired;

    @Column(name = "domain_approval_required", nullable = false)
    private boolean domainApprovalRequired;

    @Column(name = "infra_approval_required", nullable = false)
    private boolean infraApprovalRequired;

    private ProjectApprovalPolicyEntity(Long projectId,
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

    public static ProjectApprovalPolicyEntity from(ProjectApprovalPolicy policy) {
        return new ProjectApprovalPolicyEntity(
                policy.getProjectId(),
                policy.isChangeApprovalRequired(),
                policy.isDeploymentApprovalRequired(),
                policy.isDomainApprovalRequired(),
                policy.isInfraApprovalRequired()
        );
    }

    public void updateFrom(ProjectApprovalPolicy policy) {
        changeApprovalRequired = policy.isChangeApprovalRequired();
        deploymentApprovalRequired = policy.isDeploymentApprovalRequired();
        domainApprovalRequired = policy.isDomainApprovalRequired();
        infraApprovalRequired = policy.isInfraApprovalRequired();
    }

    public ProjectApprovalPolicy toDomain() {
        return new ProjectApprovalPolicy(
                projectId,
                changeApprovalRequired,
                deploymentApprovalRequired,
                domainApprovalRequired,
                infraApprovalRequired
        );
    }
}
