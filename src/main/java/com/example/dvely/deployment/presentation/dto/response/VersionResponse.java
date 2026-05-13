package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트 배포 버전 정보")
public record VersionResponse(
        @Schema(description = "버전 ID") Long versionId,
        @Schema(description = "버전명 (예: v1.0.0)") String versionName,
        @Schema(description = "기준 merge 커밋 SHA") String commitSha,
        @Schema(description = "PR 제목 또는 merge 커밋 메시지") String title,
        @Schema(description = "배포 상태 (DRAFT, PREVIEW_READY, LIVE, FAILED)") String deployStatus,
        @Schema(description = "merge 시각") LocalDateTime mergedAt
) {
}
