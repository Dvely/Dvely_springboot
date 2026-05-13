package com.example.dvely.deployment.application.result;

import java.time.LocalDateTime;

public record DeploymentHistoryResult(
        Long historyId,
        Long projectId,
        String deployTargetType,
        String versionLabel,
        String deployedUrl,
        String status,
        LocalDateTime triggeredAt,
        LocalDateTime updatedAt
) {}
