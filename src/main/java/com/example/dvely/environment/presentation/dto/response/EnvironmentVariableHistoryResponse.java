package com.example.dvely.environment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "환경변수 변경 이력. 값 자체는 기록/노출되지 않습니다(무엇이/언제/누가 바뀌었는지만 제공).")
public record EnvironmentVariableHistoryResponse(
        @Schema(description = "이력 ID", example = "10")
        Long historyId,

        @Schema(description = "대상 환경변수 ID. 변수가 삭제된 뒤에는 null일 수 있습니다.", example = "2", nullable = true)
        Long environmentVariableId,

        @Schema(description = "이력 시점의 스코프", example = "PRODUCTION")
        String scope,

        @Schema(description = "이력 시점의 키", example = "STRIPE_SECRET_KEY")
        String key,

        @Schema(description = "변경 종류", example = "UPDATED")
        String action,

        @Schema(description = "이력 시점의 secret 여부", example = "true")
        boolean secret,

        @Schema(description = "이 변경으로 값이 실제로 바뀌었는지 여부(값 자체는 기록하지 않음)", example = "true")
        boolean valueChanged,

        @Schema(description = "변경을 수행한 유저 ID", example = "7")
        Long actorUserId,

        @Schema(description = "변경 시각")
        LocalDateTime createdAt
) {
}
