package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 이력 응답")
public record DeploymentHistoryResponse(
        @Schema(description = "배포 이력 ID") Long historyId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "배포 대상 유형 (LATEST | VERSION)") String deployTargetType,
        @Schema(description = "버전 라벨. PENDING LATEST 요청만 worker 확정 전 null 가능") String versionLabel,
        @Schema(description = "배포된 GitHub Pages URL") String deployedUrl,
        @Schema(description = "배포 상태 (PENDING | IN_PROGRESS | LIVE | FAILED)") String status,
        @Schema(description = "실패 분류. 성공·진행 중이면 null. 분류를 붙이기 전에 실패한 옛 이력도 null 이다. "
                + "RESULT_UNKNOWN 은 실패가 아니라 결과를 확인하지 못했다는 뜻이라 화면에서 실패로 단정하면 안 된다.",
                allowableValues = {"WORKFLOW_FAILED", "RESULT_UNKNOWN", "RETRY_EXHAUSTED"}, nullable = true)
        String errorCode,
        @Schema(description = "실패 사유 상세. 성공·진행 중이면 null", nullable = true) String errorMessage,
        @Schema(description = "배포 트리거 시각") LocalDateTime triggeredAt,
        @Schema(description = "상태 마지막 변경 시각") LocalDateTime updatedAt,
        @Schema(description = "재시도로 생성된 이력인 경우 원본 배포 이력 ID. 재시도가 아니면 null", nullable = true)
        Long retriedFromHistoryId
) {}
