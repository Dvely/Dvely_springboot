package com.example.dvely.project.presentation.dto.response;

import java.time.LocalDateTime;

public record ProjectInfrastructureSettingsResponse(
        Long projectId,
        Long cloudConnectionId,
        String provider,
        String displayName,
        String region,
        String status,
        LocalDateTime lastCheckedAt,
        LocalDateTime updatedAt
) {
}
