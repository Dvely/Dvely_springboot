package com.example.dvely.approval.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "사용자 승인 정보")
public record ApprovalResponse(
        @Schema(description = "승인 ID", example = "34") Long approvalId,
        @Schema(description = "프로젝트 ID") Long projectId,

        @Schema(description = "대화 ID. standalone 승인(예: 인프라 설정 변경)은 연결된 대화가 없어 null", nullable = true)
        Long conversationId,

        @Schema(description = "연결된 Agent task ID. standalone 승인은 taskId가 없어 null", nullable = true)
        String taskId,

        @Schema(description = "승인 유형. RESULT는 계획 승인이 아니라 CODE 실행 '결과'(preview+diff) 확인 승인 — " +
                "ResultApprovalGate가 마지막 CODE step 완료 직후에만 생성합니다.",
                allowableValues = {"CHANGE", "DEPLOYMENT", "DOMAIN_BINDING", "INFRA_OPERATION", "RESULT"}, example = "DEPLOYMENT")
        String type,

        @Schema(description = "승인 상태. PENDING만 approve/reject 가능(그 외 호출 시 409)", allowableValues = {"PENDING", "APPROVED", "REJECTED", "CANCELLED"}, example = "PENDING")
        String status,

        @Schema(description = "승인창에 표시되는 한 줄 요약. 서비스/비용 영향이 있으면 [서비스 영향]/[비용 증가 가능] 마커가 앞에 붙을 수 있음")
        String summary,

        @Schema(description = "승인 생성 시각") LocalDateTime createdAt,
        @Schema(description = "APPROVED/REJECTED/CANCELLED로 확정된 시각. PENDING이면 null", nullable = true) LocalDateTime decidedAt
) {
}
