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

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "deploy_target_type", nullable = false)
    private String deployTargetType;

    @Column(name = "version_label")
    private String versionLabel;

    @Column(name = "deployed_url")
    private String deployedUrl;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "workflow_run_id")
    private Long workflowRunId;

    @Column(name = "correlation_id", nullable = false, length = 36)
    private String correlationId;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "workflow_head_sha", length = 40)
    private String workflowHeadSha;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "merged_by", length = 100)
    private String mergedBy;

    @Column(name = "merged_by_avatar_url", length = 1000)
    private String mergedByAvatarUrl;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "agent_task_id", length = 64)
    private String taskId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "triggered_at", updatable = false)
    private LocalDateTime triggeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static DeploymentHistoryEntity from(DeploymentHistory history) {
        DeploymentHistoryEntity entity = new DeploymentHistoryEntity();
        entity.updateAll(history);
        return entity;
    }

    public void updateFrom(DeploymentHistory history) {
        updateAll(history);
    }

    private void updateAll(DeploymentHistory history) {
        ownerUserId = history.getOwnerUserId();
        projectId = history.getProjectId();
        deployTargetType = history.getDeployTargetType().name();
        versionLabel = history.getVersionLabel();
        deployedUrl = history.getDeployedUrl();
        status = history.getStatus().name();
        workflowRunId = history.getWorkflowRunId();
        correlationId = history.getCorrelationId();
        commitSha = history.getCommitSha();
        workflowHeadSha = history.getWorkflowHeadSha();
        title = history.getTitle();
        description = history.getDescription();
        mergedBy = history.getMergedBy();
        mergedByAvatarUrl = history.getMergedByAvatarUrl();
        prNumber = history.getPrNumber();
        mergedAt = history.getMergedAt();
        taskId = history.getTaskId();
        errorMessage = history.getErrorMessage();
        attempt = history.getAttempt();
        maxAttempts = history.getMaxAttempts();
        nextRunAt = history.getNextRunAt();
        leaseOwner = history.getLeaseOwner();
        leaseUntil = history.getLeaseUntil();
    }

    public DeploymentHistory toDomain() {
        return new DeploymentHistory(
                id,
                ownerUserId,
                projectId,
                DeployTargetType.valueOf(deployTargetType),
                versionLabel,
                deployedUrl,
                DeployStatus.valueOf(status),
                workflowRunId,
                correlationId,
                commitSha,
                workflowHeadSha,
                title,
                description,
                mergedBy,
                mergedByAvatarUrl,
                prNumber,
                mergedAt,
                taskId,
                errorMessage,
                attempt,
                maxAttempts,
                nextRunAt,
                leaseOwner,
                leaseUntil,
                triggeredAt,
                updatedAt
        );
    }
}
