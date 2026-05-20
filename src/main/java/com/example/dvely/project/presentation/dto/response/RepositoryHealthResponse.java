package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로젝트 저장소 health 응답")
public record RepositoryHealthResponse(
        @Schema(
                description = "저장소 접근 상태",
                allowableValues = {"HEALTHY", "REPOSITORY_NOT_FOUND", "ACCESS_DENIED", "PERMISSION_MISMATCH", "UNKNOWN_ERROR"},
                example = "HEALTHY"
        )
        String health
) {
}
