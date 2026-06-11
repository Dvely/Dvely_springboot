package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

public record ProjectInfrastructureSettingsResult(
        Long projectId,
        Long cloudConnectionId,
        String provider,
        String displayName,
        String region,
        String status,
        LocalDateTime updatedAt
) {
}
