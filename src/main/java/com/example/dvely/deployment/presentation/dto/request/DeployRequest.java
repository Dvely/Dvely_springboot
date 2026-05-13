package com.example.dvely.deployment.presentation.dto.request;

import com.example.dvely.deployment.domain.value.DeployTargetType;
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
        String versionName
) {
}
