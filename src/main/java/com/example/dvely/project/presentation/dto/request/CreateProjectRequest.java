package com.example.dvely.project.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank String name,
        @NotBlank String startMode,
        String templateType,
        @NotBlank String draftMode
) {
}
