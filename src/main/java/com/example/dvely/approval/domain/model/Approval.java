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
        this.taskId = requireText(taskId, "taskId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.summary = requireText(summary, "summary");
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
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
