package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "프로젝트 인프라 설정 저장/변경 요청. 전체 문서 PUT — 필드 4개 모두 필수")
public record UpdateProjectInfrastructureConfigurationRequest(
        @Schema(description = "배포 아키텍처", allowableValues = {"SERVER", "CONTAINER", "SERVERLESS"}, example = "CONTAINER")
        @NotBlank String deploymentArchitecture,

        @Schema(description = "컴퓨팅 티어(provider-중립)", allowableValues = {"MICRO", "SMALL", "MEDIUM", "LARGE"}, example = "SMALL")
        @NotBlank String computeTier,

        @Schema(description = "스토리지 종류", allowableValues = {"NONE", "OBJECT_STORAGE"}, example = "OBJECT_STORAGE")
        @NotBlank String storageType,

        @Schema(description = "네트워크 공개 범위", allowableValues = {"PUBLIC", "PRIVATE"}, example = "PUBLIC")
        @NotBlank String networkAccess
) {
}
