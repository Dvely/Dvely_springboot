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
        @Schema(description = "배포 트리거 시각") LocalDateTime triggeredAt,
        @Schema(description = "상태 마지막 변경 시각") LocalDateTime updatedAt
) {}
