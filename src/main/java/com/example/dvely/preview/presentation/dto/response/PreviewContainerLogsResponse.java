package com.example.dvely.preview.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Preview 컨테이너 로그 응답")
public record PreviewContainerLogsResponse(
        @Schema(description = "Preview 세션 ID") String sessionId,
        @Schema(description = "Docker 컨테이너 실행 여부") boolean containerRunning,
        @Schema(description = "stdout+stderr 병합 로그 텍스트. 각 줄은 Docker 타임스탬프로 시작. "
                + "컨테이너가 이미 제거된 세션은 빈 문자열") String logText
) {
}
