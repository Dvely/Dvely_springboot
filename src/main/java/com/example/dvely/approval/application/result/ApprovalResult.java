package com.example.dvely.approval.application.result;

import java.time.LocalDateTime;

public record ApprovalResult(
        Long approvalId,
        Long projectId,
        Long conversationId,
        String taskId,
        String type,
        String status,
        String summary,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
