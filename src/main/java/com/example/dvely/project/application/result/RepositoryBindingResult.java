package com.example.dvely.project.application.result;

public record RepositoryBindingResult(
        String sourceRepository,
        String deploymentRepository,
        String visibility,
        String status,
        String health
) {
}
