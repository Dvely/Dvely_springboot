package com.example.dvely.project.presentation.dto.response;

public record RepositoryBindingResponse(
        String sourceRepository,
        String deploymentRepository,
        String visibility,
        String status,
        String health
) {
}
