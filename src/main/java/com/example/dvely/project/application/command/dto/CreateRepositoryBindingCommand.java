package com.example.dvely.project.application.command.dto;

public record CreateRepositoryBindingCommand(
        String bindingType,
        String repositoryFullName,
        String repositoryName,
        String visibility
) {
}
