package com.example.dvely.project.application.command.dto;

public record UpdateRepositoryBindingCommand(
        String deploymentRepository,
        String visibility
) {
}
