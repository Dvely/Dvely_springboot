package com.example.dvely.deployment.presentation.dto.request;

import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.project.domain.value.FrontendHostingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "배포 요청")
public record DeployRequest(
        @Schema(
                description = "배포 기준 타입. LATEST: 최신 작업(default branch HEAD), VERSION: 특정 git tag 기준",
                allowableValues = {"LATEST", "VERSION"}
        )
        @NotNull(message = "deployTargetType은 필수입니다")
        DeployTargetType deployTargetType,

        @Schema(description = "배포할 버전명 (git tag). deployTargetType이 VERSION일 때 필수입니다. 예: v1.0.0")
        String versionName,

        @Schema(
                description = "프론트 호스팅 방식. 지정하면 이 프로젝트의 설정을 그 값으로 바꾸고 이후 배포도 그대로 따른다. "
                        + "생략 시 프로젝트의 현재 설정(기본 GITHUB_PAGES)을 쓴다.",
                allowableValues = {"GITHUB_PAGES", "S3", "EC2"},
                nullable = true
        )
        FrontendHostingType frontendHostingType
) {
}
