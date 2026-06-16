package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로젝트별 Agent 승인 정책")
public record ProjectChatSettingsResponse(
        Long projectId,
        boolean changeApprovalRequired,
        boolean deploymentApprovalRequired,
        boolean domainApprovalRequired,
        boolean infraApprovalRequired
) {
}
