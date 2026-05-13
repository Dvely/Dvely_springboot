package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record VersionResult(
        Long versionId,
        String versionName,
        String commitSha,
        String title,
        String deployStatus,
        LocalDateTime mergedAt
) {
}
