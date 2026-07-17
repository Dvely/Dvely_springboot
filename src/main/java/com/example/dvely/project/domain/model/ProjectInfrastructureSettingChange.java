package com.example.dvely.project.domain.model;

import com.example.dvely.project.domain.value.InfrastructureChangeAction;
import com.example.dvely.project.domain.value.InfrastructureChangeStatus;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One row serves two purposes at once (design D4): it is both the append-only audit trail
 * (BI-129) and, while {@code status == PENDING_APPROVAL}, the payload an Approval is waiting on.
 * Unlike environment variable history (which deliberately drops values for secrecy), the full
 * {@link InfrastructureConfiguration} snapshot is stored here because these four enum fields are
 * not secret and "what changed to what" is the entire point of the history feature.
 */
public class ProjectInfrastructureSettingChange {

    private final Long id;
    private final Long projectId;
    private final InfrastructureChangeAction action;
    private InfrastructureChangeStatus status;
    private final InfrastructureConfiguration configuration;
    private final Long approvalId;
    private final Long actorUserId;
    private final LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    /** Full/restore constructor — used directly by the entity's {@code toDomain()} and by tests. */
    public ProjectInfrastructureSettingChange(Long id,
                                              Long projectId,
                                              InfrastructureChangeAction action,
                                              InfrastructureChangeStatus status,
                                              InfrastructureConfiguration configuration,
                                              Long approvalId,
                                              Long actorUserId,
                                              LocalDateTime createdAt,
                                              LocalDateTime decidedAt) {
        this.id = id;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.approvalId = approvalId;
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
    }

    /** Immediate-apply path (infraApprovalRequired == false): decided the instant it is recorded. */
    public static ProjectInfrastructureSettingChange applied(Long projectId,
                                                              InfrastructureChangeAction action,
                                                              InfrastructureConfiguration configuration,
                                                              Long actorUserId) {
        return new ProjectInfrastructureSettingChange(
                null, projectId, action, InfrastructureChangeStatus.APPLIED,
                configuration, null, actorUserId, null, LocalDateTime.now()
        );
    }

    /** Approval-gated path: the change waits at PENDING_APPROVAL until {@link #markApplied()}/{@link #markRejected()}. */
    public static ProjectInfrastructureSettingChange pendingApproval(Long projectId,
                                                                      InfrastructureChangeAction action,
                                                                      InfrastructureConfiguration configuration,
                                                                      Long approvalId,
                                                                      Long actorUserId) {
        return new ProjectInfrastructureSettingChange(
                null, projectId, action, InfrastructureChangeStatus.PENDING_APPROVAL,
                configuration, Objects.requireNonNull(approvalId, "approvalId must not be null"),
                actorUserId, null, null
        );
    }

    public void markApplied() {
        decide(InfrastructureChangeStatus.APPLIED);
    }

    public void markRejected() {
        decide(InfrastructureChangeStatus.REJECTED);
    }

    // Mirrors Approval#decide's PENDING guard — a change can only leave PENDING_APPROVAL once,
    // which is what makes a concurrent double-decide (e.g. approve+reject racing) fail loudly
    // (409) instead of silently double-applying or corrupting the setting.
    private void decide(InfrastructureChangeStatus next) {
        if (status != InfrastructureChangeStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("이미 확정된 인프라 설정 변경입니다. changeId=" + id);
        }
        status = next;
        decidedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public InfrastructureChangeAction getAction() {
        return action;
    }

    public InfrastructureChangeStatus getStatus() {
        return status;
    }

    public InfrastructureConfiguration getConfiguration() {
        return configuration;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }
}
