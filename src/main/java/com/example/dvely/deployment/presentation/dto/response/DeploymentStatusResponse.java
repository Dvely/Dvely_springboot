package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 상태 응답")
public record DeploymentStatusResponse(
        @Schema(description = "배포 이력 ID") Long historyId,
        @Schema(description = "프로젝트 ID") Long projectId,
        @Schema(description = "배포 대상 유형 (LATEST | VERSION)") String deployTargetType,
        @Schema(description = "버전 라벨") String versionLabel,
        @Schema(description = "배포 URL") String deployedUrl,

        @Schema(description = "배포 상태 (IN_PROGRESS | LIVE | FAILED)") String status,

        @Schema(description = "GitHub Actions 빌드 상태. IN_PROGRESS일 때만 유효. " +
                              "queued(대기) | in_progress(빌드 중) | completed(빌드 완료)")
        String buildStatus,

        @Schema(description = "GitHub Actions 빌드 결론. buildStatus가 completed일 때만 유효. " +
                              "success | failure | cancelled | null")
        String buildConclusion,

        @Schema(description = "배포 트리거 시각") LocalDateTime triggeredAt,
        @Schema(description = "상태 마지막 변경 시각") LocalDateTime updatedAt
) {}
