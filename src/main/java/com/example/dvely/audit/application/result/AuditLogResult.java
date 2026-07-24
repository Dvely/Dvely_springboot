package com.example.dvely.audit.application.result;

import java.time.LocalDateTime;

/** Read model for one {@code audit_logs} row (design §6 response shape). */
public record AuditLogResult(
        Long auditLogId,
        String category,
        String action,
        String outcome,
        String actorType,
        Long actorUserId,
        String resourceType,
        String resourceId,
        String taskId,
        Long approvalId,
        String detail,
        String errorSummary,
        LocalDateTime createdAt
) {
}
