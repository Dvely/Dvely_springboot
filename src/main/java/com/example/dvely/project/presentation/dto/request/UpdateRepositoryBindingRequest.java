package com.example.dvely.project.presentation.dto.request;

public record UpdateRepositoryBindingRequest(
        String deploymentRepository,
        String visibility
) {
}
