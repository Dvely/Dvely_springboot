package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프로젝트 Chat 승인 정책 수정 요청. changeApprovalRequired~infraApprovalRequired 4개 필드는 전체 문서 " +
        "PUT과 동일하게 필수(부분 수정 불가). resultApprovalRequired만 예외적으로 nullable — null이면 현재 값을 유지합니다 " +
        "(구버전 FE가 이 필드를 모르고 보내는 요청도 그대로 동작하도록, design D4/§5.5).")
public record UpdateProjectChatSettingsRequest(
        @Schema(description = "CODE(코드 변경) 작업 승인 필요 여부") @NotNull Boolean changeApprovalRequired,
        @Schema(description = "DEPLOY(배포) 작업 승인 필요 여부") @NotNull Boolean deploymentApprovalRequired,
        @Schema(description = "DOMAIN_BIND(도메인 연결/해제) 작업 승인 필요 여부") @NotNull Boolean domainApprovalRequired,
        @Schema(description = "INFRA_OPERATE 중 서비스/비용 영향 작업 승인 필요 여부") @NotNull Boolean infraApprovalRequired,
        @Schema(description = "실행 결과(preview·diff) 확인 후 main 반영 승인 필요 여부. null이면 현재 값 유지", nullable = true)
        Boolean resultApprovalRequired
) {
}
