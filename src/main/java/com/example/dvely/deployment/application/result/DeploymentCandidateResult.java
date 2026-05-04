package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeploymentCandidateResult(
        Long versionId,
        String versionName,
        String commitSha,
        String title,
        String deployStatus,
        String deployedUrl,
        LocalDateTime deployedAt
) {
}
