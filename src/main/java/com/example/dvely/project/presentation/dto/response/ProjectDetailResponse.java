package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트 상세 정보")
public record ProjectDetailResponse(
        @Schema(description = "프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "프로젝트 이름", example = "my-landing")
        String name,

        @Schema(description = "프로젝트 상태", allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}, example = "DRAFT")
        String status,

        @Schema(description = "프로젝트 시작 방식", example = "blank")
        String startMode,

        @Schema(description = "템플릿 유형")
        String templateType,

        @Schema(description = "초안 생성 방식", example = "fast")
        String draftMode,

        @Schema(description = "프로젝트 생성 시각")
        LocalDateTime createdAt,

        @Schema(description = "프로젝트 마지막 수정 시각")
        LocalDateTime updatedAt
) {
}
