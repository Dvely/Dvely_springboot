package com.example.dvely.environment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "환경변수 정보. secret=true이면 value는 항상 null입니다(어떤 응답에서도 평문/부분 노출 없음).")
public record EnvironmentVariableResponse(
        @Schema(description = "환경변수 ID", example = "1")
        Long environmentVariableId,

        @Schema(description = "적용 스코프", example = "PREVIEW")
        String scope,

        @Schema(description = "환경변수 키 (대소문자 구분)", example = "API_BASE_URL")
        String key,

        @Schema(description = "환경변수 값. secret=true이면 항상 null", example = "https://api.example.com", nullable = true)
        String value,

        @Schema(description = "민감 정보 여부. true로 설정한 뒤에는 false로 되돌릴 수 없습니다.", example = "false")
        boolean secret,

        @Schema(description = "생성 시각")
        LocalDateTime createdAt,

        @Schema(description = "마지막 수정 시각")
        LocalDateTime updatedAt
) {
}
