package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record VersionDetailResult(
        Long versionId,
        String versionName,
        String commitSha,
        String title,
        String description,
        String deployStatus,
        String deployedUrl,
        String mergedBy,
        String mergedByAvatarUrl,
        Integer prNumber,
        LocalDateTime mergedAt
) {
}
