package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 버전 상세 정보")
public record VersionDetailResponse(
        @Schema(description = "버전 ID") Long versionId,
        @Schema(description = "버전명 (예: v1.0.0)") String versionName,
        @Schema(description = "기준 merge 커밋 SHA") String commitSha,
        @Schema(description = "PR 제목 또는 merge 커밋 메시지") String title,
        @Schema(description = "PR 본문 또는 커밋 메시지 본문") String description,
        @Schema(description = "배포 상태 (DRAFT, PREVIEW_READY, LIVE, FAILED)") String deployStatus,
        @Schema(description = "배포된 서비스 URL. 배포 전이면 null") String deployedUrl,
        @Schema(description = "merge한 유저의 GitHub 로그인명") String mergedBy,
        @Schema(description = "merge한 유저의 GitHub 프로필 이미지 URL") String mergedByAvatarUrl,
        @Schema(description = "연결된 PR 번호. PR 없이 직접 merge된 경우 null") Integer prNumber,
        @Schema(description = "merge 시각") LocalDateTime mergedAt
) {
}
