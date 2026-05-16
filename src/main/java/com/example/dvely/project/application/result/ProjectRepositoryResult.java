package com.example.dvely.project.application.result;

public record ProjectRepositoryResult(
        Long projectId,
        String repositoryFullName,
        String repositoryVisibility,
        String bindingStatus,
        String repositoryHealth
) {
}
