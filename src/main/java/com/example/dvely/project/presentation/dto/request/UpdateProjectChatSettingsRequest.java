package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프로젝트 Chat 승인 정책 수정 요청")
public record UpdateProjectChatSettingsRequest(
        @NotNull Boolean changeApprovalRequired,
        @NotNull Boolean deploymentApprovalRequired,
        @NotNull Boolean domainApprovalRequired,
        @NotNull Boolean infraApprovalRequired
) {
}
