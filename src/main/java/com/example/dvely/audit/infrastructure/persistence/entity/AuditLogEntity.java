package com.example.dvely.audit.infrastructure.persistence.entity;

import com.example.dvely.audit.domain.model.AuditLog;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
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

/**
 * JPA row for {@code audit_logs} (V30 migration). Append-only — no {@code updateFrom(...)}, no
 * setters beyond what Hibernate needs for hydration; the only thing that ever mutates a written
 * row is the retention scheduler's bulk {@code DELETE} (design §8), which does not go through this
 * entity at all.
 *
 * <p>Enum columns are plain {@code String} + {@code valueOf}, never {@code @Enumerated}
 * (design §2.1 validate checklist, following the approval/cloudconnection precedent) — an enum
 * constant reorder must never silently renumber stored values. Every nullable column here is
 * deliberately {@code nullable = true} (the JPA default, stated explicitly for clarity) to mirror
 * the V30 DDL exactly; {@link com.example.dvely.audit.AuditLogSchemaTest} asserts the real,
 * running schema still agrees (issue #70 — {@code ddl-auto: validate} does not check
 * nullability).</p>
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long id;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "resource_type", length = 30)
    private String resourceType;

    @Column(name = "resource_id", length = 255)
    private String resourceId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private AuditLogEntity(String category,
                           String action,
                           String outcome,
                           String actorType,
                           Long actorUserId,
                           Long projectId,
                           String resourceType,
                           String resourceId,
                           String taskId,
                           Long approvalId,
                           String detail,
                           String errorSummary) {
        this.category = category;
        this.action = action;
        this.outcome = outcome;
        this.actorType = actorType;
        this.actorUserId = actorUserId;
        this.projectId = projectId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.taskId = taskId;
        this.approvalId = approvalId;
        this.detail = detail;
        this.errorSummary = errorSummary;
    }

    public static AuditLogEntity from(AuditLog auditLog) {
        return new AuditLogEntity(
                auditLog.getCategory().name(),
                auditLog.getAction().name(),
                auditLog.getOutcome().name(),
                auditLog.getActorType().name(),
                auditLog.getActorUserId(),
                auditLog.getProjectId(),
                auditLog.getResourceType(),
                auditLog.getResourceId(),
                auditLog.getTaskId(),
                auditLog.getApprovalId(),
                auditLog.getDetail(),
                auditLog.getErrorSummary()
        );
    }

    public AuditLog toDomain() {
        return new AuditLog(
                id,
                AuditAction.valueOf(action),
                AuditOutcome.valueOf(outcome),
                AuditActorType.valueOf(actorType),
                actorUserId,
                projectId,
                resourceType,
                resourceId,
                taskId,
                approvalId,
                detail,
                errorSummary,
                createdAt
        );
    }
}
