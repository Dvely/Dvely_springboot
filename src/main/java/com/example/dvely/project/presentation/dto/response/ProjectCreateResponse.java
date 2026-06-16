package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "프로젝트 생성 응답")
public record ProjectCreateResponse(
        @Schema(description = "생성된 프로젝트 ID", example = "12")
        Long projectId,

        @Schema(description = "프로젝트 이름", example = "my-landing")
        String name,

        @Schema(description = "프로젝트 상태", allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}, example = "DRAFT")
        String status,

        @Schema(description = "초기 코드 생성 Agent task ID")
        String taskId,

        @Schema(description = "초기 코드 생성 task 상태")
        String taskStatus,

        @Schema(description = "초기 코드 생성에 필요한 승인 ID")
        List<Long> approvalIds
) {
}
