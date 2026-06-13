package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "프로젝트 저장소 커밋 정보")
public record ProjectCommitResponse(
        @Schema(description = "커밋 SHA", example = "a1b2c3d4")
        String sha,

        @Schema(description = "커밋 메시지", example = "Add landing page")
        String message,

        @Schema(description = "커밋 작성자", example = "qeploy")
        String author,

        @Schema(description = "커밋 시각")
        OffsetDateTime committedAt,

        @Schema(description = "현재 시각 기준 상대 커밋 시간", example = "2시간 전")
        String relativeTime
) {
}
