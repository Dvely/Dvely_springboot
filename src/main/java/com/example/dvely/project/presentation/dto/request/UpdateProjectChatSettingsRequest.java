package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프로젝트 Chat 승인 정책 수정 요청. 전체 문서 PUT과 동일하게 4개 필드 모두 필수 — 부분 수정은 지원하지 않습니다.")
public record UpdateProjectChatSettingsRequest(
        @Schema(description = "CODE(코드 변경) 작업 승인 필요 여부") @NotNull Boolean changeApprovalRequired,
        @Schema(description = "DEPLOY(배포) 작업 승인 필요 여부") @NotNull Boolean deploymentApprovalRequired,
        @Schema(description = "DOMAIN_BIND(도메인 연결/해제) 작업 승인 필요 여부") @NotNull Boolean domainApprovalRequired,
        @Schema(description = "INFRA_OPERATE 중 서비스/비용 영향 작업 승인 필요 여부") @NotNull Boolean infraApprovalRequired
) {
}
