package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "GitHub 저장소 정보")
public record GithubRepositoryResponse(
        @Schema(description = "owner/repo 형식의 저장소 전체 이름", example = "qeploy/my-landing-repo")
        String fullName,

        @Schema(description = "저장소 이름", example = "my-landing-repo")
        String name,

        @Schema(description = "저장소 소유자 GitHub 로그인명", example = "qeploy")
        String owner,

        @Schema(description = "GitHub 저장소 설명")
        String description,

        @Schema(description = "저장소 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PUBLIC")
        String visibility,

        @Schema(description = "기본 브랜치명", example = "main")
        String defaultBranch,

        @Schema(description = "GitHub 저장소 마지막 수정 시각")
        OffsetDateTime updatedAt
) {
}
