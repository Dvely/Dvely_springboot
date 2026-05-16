package com.example.dvely.project.application.command.dto;

public record ConnectProjectRepositoryCommand(
        String repositoryMode,
        String repositoryName,
        String repositoryFullName,
        String repositoryVisibility
) {
}
