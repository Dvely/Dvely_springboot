package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로젝트 GitHub 저장소 연결 결과")
public record ProjectRepositoryResponse(
        @Schema(description = "프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "연결된 GitHub 저장소 전체 이름", example = "qeploy/my-landing-repo")
        String repositoryFullName,

        @Schema(description = "저장소 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PRIVATE")
        String repositoryVisibility,

        @Schema(description = "저장소 연결 상태", allowableValues = {"NOT_BOUND", "BOUND"}, example = "BOUND")
        String bindingStatus,

        @Schema(
                description = "저장소 health 상태",
                allowableValues = {"HEALTHY", "REPOSITORY_NOT_FOUND", "ACCESS_DENIED", "PERMISSION_MISMATCH", "UNKNOWN_ERROR"},
                example = "HEALTHY"
        )
        String repositoryHealth
) {
}
