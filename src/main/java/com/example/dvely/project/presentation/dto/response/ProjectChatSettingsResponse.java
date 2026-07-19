package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로젝트별 Agent 승인 정책. true인 항목은 Agent가 해당 작업을 실행하기 전(또는 결과를 main에 반영하기 전) " +
        "사용자 승인을 요구합니다.")
public record ProjectChatSettingsResponse(
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "CODE(코드 변경) 작업 승인 필요 여부") boolean changeApprovalRequired,
        @Schema(description = "DEPLOY(배포) 작업 승인 필요 여부") boolean deploymentApprovalRequired,
        @Schema(description = "DOMAIN_BIND(도메인 연결/해제) 작업 승인 필요 여부") boolean domainApprovalRequired,
        @Schema(description = "INFRA_OPERATE 중 서비스/비용 영향 작업(예: 인프라 설정 변경, preview 재시작) 승인 필요 여부. 기본값 true") boolean infraApprovalRequired,
        @Schema(description = "실행 결과(preview·diff) 확인 후 main 반영(RESULT) 승인 필요 여부. 기본값 true. " +
                "false면 CODE 작업 완료 후에도 결과 승인 게이트 없이 현행(배포 시점 merge) 흐름을 따릅니다.")
        boolean resultApprovalRequired
) {
}
