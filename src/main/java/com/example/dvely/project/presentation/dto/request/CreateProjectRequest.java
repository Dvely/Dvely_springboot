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

        @Schema(description = "콘텐츠 템플릿 식별자. startMode가 template일 때 사용한다. "
                            + "현재는 프로젝트에 저장만 되고 코드 생성에 반영되지 않는다 — 템플릿 기능 착수 시 사용한다. "
                            + "빌드 프레임워크를 담는 필드가 아니다(그 값은 저장소에서 자동 감지한다).",
                example = "landing")
        @Size(max = 50)
        String templateType,

        @Schema(description = "초안 생성 방식", allowableValues = {"fast", "quality"}, example = "fast")
        @NotBlank
        @Pattern(regexp = "(?i)fast|quality")
        String draftMode
) {
}
