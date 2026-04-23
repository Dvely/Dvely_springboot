package com.example.dvely.project.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateRepositoryBindingRequest(
        @NotBlank String bindingType,
        String repositoryFullName,
        String repositoryName,
        String visibility
) {
}
