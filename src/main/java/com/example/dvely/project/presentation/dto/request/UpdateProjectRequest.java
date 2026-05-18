package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "프로젝트 수정 요청")
public record UpdateProjectRequest(
        @Schema(description = "변경할 프로젝트 이름", example = "new-project-name")
        @NotBlank String name
) {
}
