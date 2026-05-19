package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "프로젝트 생성 요청")
public record CreateProjectRequest(
        @Schema(description = "프로젝트 이름", example = "my-landing")
        @NotBlank String name,

        @Schema(description = "프로젝트 시작 방식", example = "blank")
        @NotBlank String startMode,

        @Schema(description = "템플릿 유형. startMode가 템플릿 기반일 때 사용", example = "landing")
        String templateType,

        @Schema(description = "초안 생성 방식. 값이 없으면 fast로 보정됩니다.", example = "fast")
        @NotBlank String draftMode
) {
}
