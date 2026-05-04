package com.example.dvely.project.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank String name,
        String repositoryMode,
        String repositoryName,
        String repositoryFullName,
        @NotBlank String startMode,
        String templateType,
        @NotBlank String draftMode,
        @NotBlank String repositoryVisibility
) {
}
