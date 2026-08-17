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
                "ResultApprovalGate가 마지막 CODE step 완료 직후에만 생성합니다. REPOSITORY_BINDING은 같은 지점에서 " +
                "저장소가 아직 연결되지 않은(NOT_BOUND) 프로젝트에만 생성되는 RESULT의 짝으로, 저장소를 만들어 연결할지 묻습니다 — " +
                "approve 시 본문으로 저장소 이름을 지정할 수 있고, 거절해도 task는 취소되지 않고 그대로 완료됩니다.",
                allowableValues = {"CHANGE", "DEPLOYMENT", "DOMAIN_BINDING", "INFRA_OPERATION", "RESULT", "REPOSITORY_BINDING"},
                example = "DEPLOYMENT")
        String type,

        @Schema(description = "승인 상태. PENDING만 approve/reject 가능(그 외 호출 시 409)", allowableValues = {"PENDING", "APPROVED", "REJECTED", "CANCELLED"}, example = "PENDING")
        String status,

        @Schema(description = "승인창에 표시되는 한 줄 요약. 서비스/비용 영향이 있으면 [서비스 영향]/[비용 증가 가능] 마커가 앞에 붙을 수 있음")
        String summary,

        @Schema(description = "승인할 때 함께 받아야 하는 입력의 스펙. null 이면 단순 승인/거절이므로 버튼만 그리면 됩니다. " +
                "값이 있으면 defaultValue 를 채운 입력 필드를 그리고, 승인 시 그 값을 field 이름으로 본문에 실어 보내세요. " +
                "현재는 REPOSITORY_BINDING(저장소 이름)에만 채워집니다.", nullable = true)
        ApprovalInputResponse input,

        @Schema(description = "승인 생성 시각") LocalDateTime createdAt,
        @Schema(description = "APPROVED/REJECTED/CANCELLED로 확정된 시각. PENDING이면 null", nullable = true) LocalDateTime decidedAt
) {
}
