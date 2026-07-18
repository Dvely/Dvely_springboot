package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프로젝트에 클라우드 연결 선택 요청. 대상 연결이 CONNECTED 상태가 아니면 409를 반환합니다.")
public record UpdateProjectInfrastructureSettingsRequest(
        @Schema(description = "선택할 클라우드 연결 ID (GET /cloud-connections로 조회)", example = "3")
        @NotNull Long cloudConnectionId
) {
}
