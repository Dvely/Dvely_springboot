package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 가능 후보 버전 정보. 과거 배포 이력 중 성공(LIVE, PREVIEW_READY)한 버전만 포함됩니다.")
public record DeploymentCandidateResponse(
        @Schema(description = "버전 ID") Long versionId,
        @Schema(description = "버전명 (예: v1.0.0)") String versionName,
        @Schema(description = "기준 merge 커밋 SHA") String commitSha,
        @Schema(description = "PR 제목 또는 merge 커밋 메시지") String title,
        @Schema(description = "마지막 성공 배포 상태 (LIVE, PREVIEW_READY)") String deployStatus,
        @Schema(description = "마지막으로 배포된 서비스 URL") String deployedUrl,
        @Schema(description = "마지막 성공 배포 시각") LocalDateTime deployedAt
) {
}
