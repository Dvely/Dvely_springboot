package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로젝트 GitHub 저장소 연결 요청")
public record ConnectProjectRepositoryRequest(
        @Schema(description = "저장소 연결 방식. create/create_new/new 또는 existing/import/import_existing 지원", example = "create")
        String repositoryMode,

        @Schema(description = "새 저장소 생성 시 사용할 저장소 이름", example = "my-landing-repo")
        String repositoryName,

        @Schema(description = "기존 저장소 연결 시 owner/repo 형식의 전체 이름", example = "dvely/my-landing-repo")
        String repositoryFullName,

        @Schema(description = "새 저장소 생성 시 공개 범위. 값이 없으면 PRIVATE", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PRIVATE")
        String repositoryVisibility
) {
}
