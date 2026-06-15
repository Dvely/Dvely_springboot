package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "프로젝트 생성 요청")
public record CreateProjectRequest(
        @Schema(description = "프로젝트 이름", example = "my-landing")
        @NotBlank String name,

        @Schema(description = "프로젝트 시작 방식", allowableValues = {"blank", "template"}, example = "blank")
        @NotBlank
        @Pattern(regexp = "(?i)blank|template")
        String startMode,

        @Schema(description = "템플릿 유형. startMode가 템플릿 기반일 때 사용", example = "landing")
        @Size(max = 50)
        String templateType,

        @Schema(description = "초안 생성 방식", allowableValues = {"fast", "quality"}, example = "fast")
        @NotBlank
        @Pattern(regexp = "(?i)fast|quality")
        String draftMode
) {
}
