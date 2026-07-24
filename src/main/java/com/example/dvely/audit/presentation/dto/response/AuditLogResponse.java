package com.example.dvely.audit.presentation.dto.response;

import java.time.LocalDateTime;

public record AuditLogResponse(
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
