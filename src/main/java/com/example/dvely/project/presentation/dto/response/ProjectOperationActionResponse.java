package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Overview 운영 조치. 프로젝트 상태에 따라 각 조치의 즉시 실행 가능 여부와 사유를 안내합니다.")
public record ProjectOperationActionResponse(
        @Schema(
                description = "조치 종류",
                allowableValues = {"DEPLOY", "MANAGE_DOMAIN", "MANAGE_CLOUD", "OPEN_AI_AGENT", "PROJECT_SETTINGS", "REMOVE_PROJECT"},
                example = "DEPLOY"
        )
        String type,

        @Schema(description = "지금 바로 실행 가능한지 여부. DEPLOY는 저장소 미연결이거나 배포 진행 중이면 false, 그 외 조치는 항상 true")
        boolean available,

        @Schema(description = "현재 상태에 대한 설명. available=false인 이유 또는 현재 상태 요약")
        String reason
) {
}
