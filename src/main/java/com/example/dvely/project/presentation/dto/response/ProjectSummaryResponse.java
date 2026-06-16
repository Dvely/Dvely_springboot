package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프로젝트 목록 항목")
public record ProjectSummaryResponse(
        @Schema(description = "프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "프로젝트 이름", example = "my-landing")
        String name,

        @Schema(description = "현재 배포 상태", allowableValues = {"DRAFT", "PENDING", "IN_PROGRESS", "PREVIEW_READY", "LIVE", "FAILED"}, example = "DRAFT")
        String deployStatus,

        @Schema(description = "현재 배포 URL. 배포 전이면 null")
        String currentUrl,

        @Schema(description = "프로젝트 마지막 수정 시각")
        LocalDateTime updatedAt,

        @Schema(description = "마지막 수정 시각의 상대 표현", example = "2시간 전")
        String updatedAtRelativeText
) {
}
