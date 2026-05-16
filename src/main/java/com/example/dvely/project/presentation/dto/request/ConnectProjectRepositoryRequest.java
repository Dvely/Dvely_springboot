package com.example.dvely.project.presentation.dto.request;

public record ConnectProjectRepositoryRequest(
        String repositoryMode,
        String repositoryName,
        String repositoryFullName,
        String repositoryVisibility
) {
}
