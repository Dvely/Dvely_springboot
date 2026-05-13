package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeployResult(
        Long deploymentId,
        Long projectId,
        String deployTargetType,
        String versionName,
        String status,
        String pagesUrl,
        LocalDateTime createdAt
) {
}
