package com.example.dvely.approval.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "사용자 승인 정보")
public record ApprovalResponse(
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
