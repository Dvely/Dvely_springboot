package com.example.dvely.audit.domain.model;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.common.security.SecretRedactor;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Append-only audit row (design §2.1/§2.3). {@code category} is never passed in independently —
 * it is always derived from {@code action} (see {@link AuditAction#category()}) so the two can
 * never disagree.
 *
 * <p>{@link #from(AuditEvent)} is the single construction path used by {@code AuditLogWriter}
 * (design §5.1) and is where the two storage-layer invariants are enforced unconditionally,
 * regardless of what the calling hook did or forgot to do:</p>
 * <ul>
 *   <li>{@code errorSummary} is passed through {@link SecretRedactor#redact} before truncation
 *       (design §7 rule 2) — a hook that forwards a raw exception message can never leak a
 *       token-shaped secret into this table.</li>
 *   <li>{@code detail}/{@code errorSummary} are defensively truncated to the column limits
 *       (1000/500 chars, design §2.1) so an oversized value can never make the INSERT itself fail
 *       — the one write this whole feature promises never to block the caller's own transaction
 *       on (design ADR-A2).</li>
 * </ul>
 */
public class AuditLog {

    private static final int DETAIL_MAX_LENGTH = 1000;
    private static final int ERROR_SUMMARY_MAX_LENGTH = 500;

    private final Long id;
    private final AuditCategory category;
    private final AuditAction action;
    private final AuditOutcome outcome;
    private final AuditActorType actorType;
    private final Long actorUserId;
    private final Long projectId;
    private final String resourceType;
    private final String resourceId;
    private final String taskId;
    private final Long approvalId;
    private final String detail;
    private final String errorSummary;
    private final LocalDateTime createdAt;

    // Review follow-up (Low-3, ad-audit-review.md): this constructor bypasses from()'s redaction/
    // truncation entirely ("values are trusted as already-valid/already-truncated") — kept
    // private, with intent-revealing access only through restore() below, so nothing outside this
    // class can construct an AuditLog without going through either from() (new rows) or restore()
    // (rehydrating an already-written row). A public constructor here would have let a future
    // caller accidentally build a row that skips both invariants (design's "구조로 강제, 신뢰가
    // 아닌" principle — same reasoning as AuditLogWriter's §5.1 leaf guarantee).
    private AuditLog(Long id,
                     AuditAction action,
                     AuditOutcome outcome,
                     AuditActorType actorType,
                     Long actorUserId,
                     Long projectId,
                     String resourceType,
                     String resourceId,
                     String taskId,
                     Long approvalId,
                     String detail,
                     String errorSummary,
                     LocalDateTime createdAt) {
        this.id = id;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.category = action.category();
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.actorType = Objects.requireNonNull(actorType, "actorType must not be null");
        this.actorUserId = actorUserId;
        this.projectId = projectId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.taskId = taskId;
        this.approvalId = approvalId;
        this.detail = detail;
        this.errorSummary = errorSummary;
        this.createdAt = createdAt;
    }

    /**
     * New-record factory — the only path {@code AuditLogWriter} uses. Applies redaction +
     * defensive truncation (see class javadoc) so every row written through this method already
     * satisfies the column constraints and the §7 secret policy, independent of what the caller
     * passed in.
     */
    public static AuditLog from(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new AuditLog(
                null,
                event.action(),
                event.outcome(),
                event.actorType(),
                event.actorUserId(),
                event.projectId(),
                event.resourceType(),
                event.resourceId(),
                event.taskId(),
                event.approvalId(),
                truncate(event.detail(), DETAIL_MAX_LENGTH),
                truncate(SecretRedactor.redact(event.errorSummary()), ERROR_SUMMARY_MAX_LENGTH),
                null
        );
    }

    /**
     * Rehydrates an {@code AuditLog} already read back from {@code audit_logs} (design §2.1) —
     * used by {@code AuditLogEntity#toDomain}. Deliberately named/exposed separately from
     * {@link #from(AuditEvent)} (Low-3 follow-up): every field here is trusted as already
     * satisfying the column constraints and §7 redaction policy because it was persisted by
     * {@code from()} in the first place, so no truncation/redaction is reapplied — doing so again
     * would be redundant at best and silently mask a schema/entity drift at worst.
     */
    public static AuditLog restore(Long id,
                                   AuditAction action,
                                   AuditOutcome outcome,
                                   AuditActorType actorType,
                                   Long actorUserId,
                                   Long projectId,
                                   String resourceType,
                                   String resourceId,
                                   String taskId,
                                   Long approvalId,
                                   String detail,
                                   String errorSummary,
                                   LocalDateTime createdAt) {
        return new AuditLog(
                id, action, outcome, actorType, actorUserId, projectId,
                resourceType, resourceId, taskId, approvalId, detail, errorSummary, createdAt
        );
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public Long getId() { return id; }
    public AuditCategory getCategory() { return category; }
    public AuditAction getAction() { return action; }
    public AuditOutcome getOutcome() { return outcome; }
    public AuditActorType getActorType() { return actorType; }
    public Long getActorUserId() { return actorUserId; }
    public Long getProjectId() { return projectId; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getTaskId() { return taskId; }
    public Long getApprovalId() { return approvalId; }
    public String getDetail() { return detail; }
    public String getErrorSummary() { return errorSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
