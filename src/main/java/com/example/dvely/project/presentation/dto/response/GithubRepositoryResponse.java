package com.example.dvely.project.presentation.dto.response;

import java.time.OffsetDateTime;

public record GithubRepositoryResponse(
        String fullName,
        String name,
        String owner,
        String description,
        String visibility,
        String defaultBranch,
        OffsetDateTime updatedAt
) {
}
