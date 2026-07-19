package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

/**
 * Read model for the "Repository Settings" screen (GET /{projectId}/settings/repository).
 * Mirrors {@code ProjectRepositorySettingsResponse} field-for-field; kept as a separate
 * application-layer record (rather than reusing {@link ProjectRepositoryResult}) because this
 * view combines persisted fields with a live GitHub lookup ({@code defaultBranch}) and adds
 * settings-specific fields ({@code connected}, {@code repositoryUrl}, {@code connectedAt},
 * {@code lastSyncedAt}) that the connect-repository response does not need.
 */
public record ProjectRepositorySettingsResult(
        Long projectId,
        boolean connected,
        String repositoryFullName,
        String repositoryUrl,
        String defaultBranch,
        String repositoryVisibility,
        String bindingStatus,
        String repositoryHealth,
        LocalDateTime connectedAt,
        LocalDateTime lastSyncedAt
) {
}
