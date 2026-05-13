package com.example.dvely.deployment.domain.model;

import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.domain.value.DeployStatus;
import java.time.LocalDateTime;
import java.util.Objects;

public class DeploymentHistory {

    private final Long id;
    private final Long projectId;
    private final DeployTargetType deployTargetType;
    private final String versionLabel;
    private final String deployedUrl;
    private DeployStatus status;
    private Long workflowRunId;
    private final LocalDateTime triggeredAt;
    private LocalDateTime updatedAt;

    // 신규 생성용
    public DeploymentHistory(Long projectId,
                              DeployTargetType deployTargetType,
                              String versionLabel,
                              String deployedUrl) {
        this.id = null;
        this.projectId = Objects.requireNonNull(projectId);
        this.deployTargetType = Objects.requireNonNull(deployTargetType);
        this.versionLabel = versionLabel;
        this.deployedUrl = Objects.requireNonNull(deployedUrl);
        this.status = DeployStatus.IN_PROGRESS;
        this.workflowRunId = null;
        this.triggeredAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 영속성 복원용
    public DeploymentHistory(Long id,
                              Long projectId,
                              DeployTargetType deployTargetType,
                              String versionLabel,
                              String deployedUrl,
                              DeployStatus status,
                              Long workflowRunId,
                              LocalDateTime triggeredAt,
                              LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.deployTargetType = deployTargetType;
        this.versionLabel = versionLabel;
        this.deployedUrl = deployedUrl;
        this.status = status;
        this.workflowRunId = workflowRunId;
        this.triggeredAt = triggeredAt;
        this.updatedAt = updatedAt;
    }

    public void complete() {
        this.status = DeployStatus.LIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = DeployStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void assignRunId(Long runId) {
        this.workflowRunId = runId;
    }

    public Long getId()                           { return id; }
    public Long getProjectId()                    { return projectId; }
    public DeployTargetType getDeployTargetType() { return deployTargetType; }
    public String getVersionLabel()               { return versionLabel; }
    public String getDeployedUrl()                { return deployedUrl; }
    public DeployStatus getStatus()               { return status; }
    public Long getWorkflowRunId()                { return workflowRunId; }
    public LocalDateTime getTriggeredAt()         { return triggeredAt; }
    public LocalDateTime getUpdatedAt()           { return updatedAt; }
}
