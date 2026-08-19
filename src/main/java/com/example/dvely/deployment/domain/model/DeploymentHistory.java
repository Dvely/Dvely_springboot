package com.example.dvely.deployment.domain.model;

import com.example.dvely.deployment.application.port.out.GithubRepoPort.ReleaseMetadata;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.domain.value.DeployFailureCode;
import com.example.dvely.project.domain.value.DeployStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public class DeploymentHistory {

    private final Long id;
    private final Long ownerUserId;
    private final Long projectId;
    private final DeployTargetType deployTargetType;
    private String versionLabel;
    private String deployedUrl;
    private DeployStatus status;
    private Long workflowRunId;
    private final String correlationId;
    private String commitSha;
    private String workflowHeadSha;
    private String title;
    private String description;
    private String mergedBy;
    private String mergedByAvatarUrl;
    private Integer prNumber;
    private LocalDateTime mergedAt;
    private final String taskId;
    private String errorMessage;
    private DeployFailureCode failureCode;
    private int attempt;
    private final int maxAttempts;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private final LocalDateTime triggeredAt;
    private LocalDateTime updatedAt;
    private final Long retriedFromHistoryId;

    public DeploymentHistory(Long ownerUserId,
                             Long projectId,
                             DeployTargetType deployTargetType,
                             String requestedVersion,
                             String taskId,
                             Long retriedFromHistoryId) {
        this(
                null,
                Objects.requireNonNull(ownerUserId),
                Objects.requireNonNull(projectId),
                Objects.requireNonNull(deployTargetType),
                requestedVersion,
                null,
                DeployStatus.PENDING,
                null,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                taskId,
                null,
                0,
                3,
                LocalDateTime.now(),
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                retriedFromHistoryId
        );
    }

    // 기존 테스트 및 레거시 이력 복원 호환용
    public DeploymentHistory(Long id,
                             Long projectId,
                             DeployTargetType deployTargetType,
                             String versionLabel,
                             String deployedUrl,
                             DeployStatus status,
                             Long workflowRunId,
                             LocalDateTime triggeredAt,
                             LocalDateTime updatedAt) {
        this(
                id,
                null,
                projectId,
                deployTargetType,
                versionLabel,
                deployedUrl,
                status,
                workflowRunId,
                "legacy-" + id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                triggeredAt,
                updatedAt,
                null
        );
    }

    /** 실패 분류가 없던 시절의 이력과 테스트를 위해 남긴다 — failureCode 는 null 이 된다. */
    public DeploymentHistory(Long id,
                             Long ownerUserId,
                             Long projectId,
                             DeployTargetType deployTargetType,
                             String versionLabel,
                             String deployedUrl,
                             DeployStatus status,
                             Long workflowRunId,
                             String correlationId,
                             String commitSha,
                             String workflowHeadSha,
                             String title,
                             String description,
                             String mergedBy,
                             String mergedByAvatarUrl,
                             Integer prNumber,
                             LocalDateTime mergedAt,
                             String taskId,
                             String errorMessage,
                             int attempt,
                             int maxAttempts,
                             LocalDateTime nextRunAt,
                             String leaseOwner,
                             LocalDateTime leaseUntil,
                             LocalDateTime triggeredAt,
                             LocalDateTime updatedAt,
                             Long retriedFromHistoryId) {
        this(id, ownerUserId, projectId, deployTargetType, versionLabel, deployedUrl, status,
                workflowRunId, correlationId, commitSha, workflowHeadSha, title, description,
                mergedBy, mergedByAvatarUrl, prNumber, mergedAt, taskId, errorMessage, null,
                attempt, maxAttempts, nextRunAt, leaseOwner, leaseUntil, triggeredAt, updatedAt,
                retriedFromHistoryId);
    }

    public DeploymentHistory(Long id,
                             Long ownerUserId,
                             Long projectId,
                             DeployTargetType deployTargetType,
                             String versionLabel,
                             String deployedUrl,
                             DeployStatus status,
                             Long workflowRunId,
                             String correlationId,
                             String commitSha,
                             String workflowHeadSha,
                             String title,
                             String description,
                             String mergedBy,
                             String mergedByAvatarUrl,
                             Integer prNumber,
                             LocalDateTime mergedAt,
                             String taskId,
                             String errorMessage,
                             DeployFailureCode failureCode,
                             int attempt,
                             int maxAttempts,
                             LocalDateTime nextRunAt,
                             String leaseOwner,
                             LocalDateTime leaseUntil,
                             LocalDateTime triggeredAt,
                             LocalDateTime updatedAt,
                             Long retriedFromHistoryId) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.deployTargetType = deployTargetType;
        this.versionLabel = versionLabel;
        this.deployedUrl = deployedUrl;
        this.status = status;
        this.workflowRunId = workflowRunId;
        this.correlationId = correlationId;
        this.commitSha = commitSha;
        this.workflowHeadSha = workflowHeadSha;
        this.title = title;
        this.description = description;
        this.mergedBy = mergedBy;
        this.mergedByAvatarUrl = mergedByAvatarUrl;
        this.prNumber = prNumber;
        this.mergedAt = mergedAt;
        this.taskId = taskId;
        this.errorMessage = errorMessage;
        this.failureCode = failureCode;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.nextRunAt = nextRunAt;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.triggeredAt = triggeredAt;
        this.updatedAt = updatedAt;
        this.retriedFromHistoryId = retriedFromHistoryId;
    }

    public void prepare(String versionLabel,
                        String deployedUrl,
                        String workflowHeadSha,
                        ReleaseMetadata metadata) {
        this.versionLabel = Objects.requireNonNull(versionLabel);
        this.deployedUrl = Objects.requireNonNull(deployedUrl);
        this.workflowHeadSha = Objects.requireNonNull(workflowHeadSha);
        this.commitSha = metadata.commitSha();
        this.title = metadata.title();
        this.description = metadata.description();
        this.mergedBy = metadata.mergedBy();
        this.mergedByAvatarUrl = metadata.mergedByAvatarUrl();
        this.prNumber = metadata.prNumber();
        this.mergedAt = metadata.mergedAt();
        this.updatedAt = LocalDateTime.now();
    }

    public void markDispatched(Long runId, String runHeadSha) {
        status = DeployStatus.IN_PROGRESS;
        workflowRunId = runId;
        if (runHeadSha != null && !runHeadSha.isBlank()) {
            workflowHeadSha = runHeadSha;
        }
        errorMessage = null;
        nextRunAt = null;
        clearLease();
        updatedAt = LocalDateTime.now();
    }

    public void retry(String errorMessage, Duration delay) {
        if (attempt >= maxAttempts) {
            fail(DeployFailureCode.RETRY_EXHAUSTED, errorMessage);
            return;
        }
        status = DeployStatus.PENDING;
        this.errorMessage = errorMessage;
        nextRunAt = LocalDateTime.now().plus(delay);
        clearLease();
        updatedAt = LocalDateTime.now();
    }

    public void complete() {
        status = DeployStatus.LIVE;
        errorMessage = null;
        failureCode = null;
        clearLease();
        updatedAt = LocalDateTime.now();
    }

    public void fail() {
        fail(failureCode, errorMessage);
    }

    public void fail(DeployFailureCode failureCode, String errorMessage) {
        status = DeployStatus.FAILED;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        nextRunAt = null;
        clearLease();
        updatedAt = LocalDateTime.now();
    }

    public void assignRunId(Long runId) {
        workflowRunId = runId;
        updatedAt = LocalDateTime.now();
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    public Long getId()                           { return id; }
    public Long getOwnerUserId()                  { return ownerUserId; }
    public Long getProjectId()                    { return projectId; }
    public DeployTargetType getDeployTargetType() { return deployTargetType; }
    public String getVersionLabel()               { return versionLabel; }
    public String getDeployedUrl()                { return deployedUrl; }
    public DeployStatus getStatus()               { return status; }
    public Long getWorkflowRunId()                { return workflowRunId; }
    public String getCorrelationId()              { return correlationId; }
    public String getCommitSha()                  { return commitSha; }
    public String getWorkflowHeadSha()            { return workflowHeadSha; }
    public String getTitle()                      { return title; }
    public String getDescription()                { return description; }
    public String getMergedBy()                   { return mergedBy; }
    public String getMergedByAvatarUrl()          { return mergedByAvatarUrl; }
    public Integer getPrNumber()                  { return prNumber; }
    public LocalDateTime getMergedAt()            { return mergedAt; }
    public String getTaskId()                     { return taskId; }
    public String getErrorMessage()               { return errorMessage; }
    public DeployFailureCode getFailureCode()     { return failureCode; }
    public int getAttempt()                       { return attempt; }
    public int getMaxAttempts()                   { return maxAttempts; }
    public LocalDateTime getNextRunAt()           { return nextRunAt; }
    public String getLeaseOwner()                 { return leaseOwner; }
    public LocalDateTime getLeaseUntil()          { return leaseUntil; }
    public LocalDateTime getTriggeredAt()         { return triggeredAt; }
    public LocalDateTime getUpdatedAt()           { return updatedAt; }
    public Long getRetriedFromHistoryId()         { return retriedFromHistoryId; }
}
