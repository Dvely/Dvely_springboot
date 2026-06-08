package com.example.dvely.approval.infrastructure.persistence.entity;

import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
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

@Entity
@Table(name = "approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "approval_type", nullable = false, length = 30)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    private ApprovalEntity(Long ownerUserId,
                           Long projectId,
                           Long conversationId,
                           String taskId,
                           String type,
                           String status,
                           String summary) {
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.type = type;
        this.status = status;
        this.summary = summary;
    }

    public static ApprovalEntity from(Approval approval) {
        return new ApprovalEntity(
                approval.getOwnerUserId(),
                approval.getProjectId(),
                approval.getConversationId(),
                approval.getTaskId(),
                approval.getType().name(),
                approval.getStatus().name(),
                approval.getSummary()
        );
    }

    public void updateFrom(Approval approval) {
        status = approval.getStatus().name();
        decidedAt = approval.getDecidedAt();
    }

    public Approval toDomain() {
        return new Approval(
                id,
                ownerUserId,
                projectId,
                conversationId,
                taskId,
                ApprovalType.valueOf(type),
                ApprovalStatus.valueOf(status),
                summary,
                createdAt,
                decidedAt
        );
    }
}
