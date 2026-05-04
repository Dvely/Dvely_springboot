package com.example.dvely.project.application.command.dto;

public record CreateProjectCommand(
        String name,
        String repositoryMode,
        String repositoryName,
        String repositoryFullName,
        String startMode,
        String templateType,
        String draftMode,
        String repositoryVisibility
) {
}
