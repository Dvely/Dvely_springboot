package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "프로젝트 활동 로그")
public record ProjectActivityLogResponse(
        @Schema(description = "활동 유형", example = "PROJECT_CREATED")
        String type,

        @Schema(description = "활동 메시지", example = "프로젝트가 생성되었습니다: my-landing")
        String message,

        @Schema(description = "활동 발생 시각")
        OffsetDateTime occurredAt
) {
}
