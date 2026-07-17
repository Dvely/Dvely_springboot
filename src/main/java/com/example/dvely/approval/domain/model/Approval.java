package com.example.dvely.approval.domain.model;

import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import java.time.LocalDateTime;
import java.util.Objects;

public class Approval {

    private final Long id;
    private final Long ownerUserId;
    private final Long projectId;
    private final Long conversationId;
    private final String taskId;
    private final ApprovalType type;
    private ApprovalStatus status;
    private final String summary;
    private final LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    public Approval(Long ownerUserId,
                    Long projectId,
                    Long conversationId,
                    String taskId,
                    ApprovalType type,
                    String summary) {
        this(
                null,
                ownerUserId,
                projectId,
                conversationId,
                taskId,
                type,
                ApprovalStatus.PENDING,
                summary,
                null,
                null
        );
    }

    public Approval(Long id,
                    Long ownerUserId,
                    Long projectId,
                    Long conversationId,
                    String taskId,
                    ApprovalType type,
                    ApprovalStatus status,
                    String summary,
                    LocalDateTime createdAt,
                    LocalDateTime decidedAt) {
        this.id = id;
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.projectId = projectId;
        this.conversationId = conversationId;
        // Standalone approvals (design D6) have no owning agent task, so taskId is now allowed
        // to be null — but if a caller does pass a non-null value it must still be real text,
        // same as before. Every existing agent-task call site already passes a non-null taskId,
        // so this loosening is behavior-preserving for them.
        this.taskId = taskId == null ? null : requireText(taskId, "taskId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.summary = requireText(summary, "summary");
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
    }

    /**
     * Creates a PENDING approval that is not tied to any agent task (design D6) — e.g. an
     * infrastructure configuration change requested directly through the API. {@code taskId}
     * and {@code conversationId} are both null: there is no task to execute on approval and no
     * chat conversation to post a follow-up assistant message to. {@link #isStandalone()}
     * distinguishes these rows so {@code ApprovalCommandService} can dispatch to a
     * {@code StandaloneApprovalHandler} instead of the agent-task approve/reject path.
     */
    public static Approval standalone(Long ownerUserId, Long projectId, ApprovalType type, String summary) {
        return new Approval(null, ownerUserId, projectId, null, null, type, ApprovalStatus.PENDING, summary, null, null);
    }

    public boolean isStandalone() {
        return taskId == null;
    }

    public void approve() {
        decide(ApprovalStatus.APPROVED);
    }

    public void reject() {
        decide(ApprovalStatus.REJECTED);
    }

    public void cancel() {
        decide(ApprovalStatus.CANCELLED);
    }

    private void decide(ApprovalStatus decision) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 승인입니다. approvalId=" + id);
        }
        status = decision;
        decidedAt = LocalDateTime.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public Long getProjectId() { return projectId; }
    public Long getConversationId() { return conversationId; }
    public String getTaskId() { return taskId; }
    public ApprovalType getType() { return type; }
    public ApprovalStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}
