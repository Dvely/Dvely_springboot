package com.example.dvely.deployment.infrastructure.persistence.entity;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.domain.value.DeployStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "deployment_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "deploy_target_type", nullable = false)
    private String deployTargetType;

    @Column(name = "version_label")
    private String versionLabel;

    @Column(name = "deployed_url", nullable = false)
    private String deployedUrl;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "workflow_run_id")
    private Long workflowRunId;

    @CreationTimestamp
    @Column(name = "triggered_at", updatable = false)
    private LocalDateTime triggeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private DeploymentHistoryEntity(Long projectId,
                                    String deployTargetType,
                                    String versionLabel,
                                    String deployedUrl,
                                    String status) {
        this.projectId = projectId;
        this.deployTargetType = deployTargetType;
        this.versionLabel = versionLabel;
        this.deployedUrl = deployedUrl;
        this.status = status;
    }

    public static DeploymentHistoryEntity from(DeploymentHistory history) {
        return new DeploymentHistoryEntity(
                history.getProjectId(),
                history.getDeployTargetType().name(),
                history.getVersionLabel(),
                history.getDeployedUrl(),
                history.getStatus().name()
        );
    }

    public void updateFrom(DeploymentHistory history) {
        this.status = history.getStatus().name();
        this.workflowRunId = history.getWorkflowRunId();
    }

    public DeploymentHistory toDomain() {
        return new DeploymentHistory(
                id,
                projectId,
                DeployTargetType.valueOf(deployTargetType),
                versionLabel,
                deployedUrl,
                DeployStatus.valueOf(status),
                workflowRunId,
                triggeredAt,
                updatedAt
        );
    }
}
