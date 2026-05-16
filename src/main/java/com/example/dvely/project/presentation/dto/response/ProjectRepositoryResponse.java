package com.example.dvely.project.presentation.dto.response;

public record ProjectRepositoryResponse(
        Long projectId,
        String repositoryFullName,
        String repositoryVisibility,
        String bindingStatus,
        String repositoryHealth
) {
}
