package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트 Repository 설정 조회 응답. 저장소가 연결되지 않은 프로젝트도 200으로 응답하며 connected=false로 표시합니다.")
public record ProjectRepositorySettingsResponse(
        @Schema(description = "프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "GitHub 저장소 연결 여부", example = "true")
        boolean connected,

        @Schema(description = "연결된 GitHub 저장소 전체 이름. 미연결 시 null", example = "qeploy/my-landing-repo")
        String repositoryFullName,

        @Schema(description = "GitHub 저장소 URL. 미연결 시 null", example = "https://github.com/qeploy/my-landing-repo")
        String repositoryUrl,

        @Schema(description = "기본 브랜치. GitHub 라이브 조회 결과이며 미연결이거나 조회에 실패하면 null", example = "main")
        String defaultBranch,

        @Schema(description = "저장소 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PRIVATE")
        String repositoryVisibility,

        @Schema(description = "저장소 연결 상태", allowableValues = {"NOT_BOUND", "BOUND"}, example = "BOUND")
        String bindingStatus,

        @Schema(
                description = "저장소 health 상태 (저장된 값. 라이브 재확인은 GET /repository-health 사용)",
                allowableValues = {"HEALTHY", "REPOSITORY_NOT_FOUND", "ACCESS_DENIED", "PERMISSION_MISMATCH", "UNKNOWN_ERROR"},
                example = "HEALTHY"
        )
        String repositoryHealth,

        @Schema(description = "저장소 연결 시각. 레거시 연결 행이거나 미연결 시 null")
        LocalDateTime connectedAt,

        @Schema(description = "webhook 기준 마지막 head 동기화 시각")
        LocalDateTime lastSyncedAt
) {
}
