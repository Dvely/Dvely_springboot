package com.example.dvely.project.application.result;

import java.time.OffsetDateTime;

public record GithubRepositoryResult(
        String fullName,
        String name,
        String owner,
        String description,
        String visibility,
        String defaultBranch,
        OffsetDateTime updatedAt
) {
}
