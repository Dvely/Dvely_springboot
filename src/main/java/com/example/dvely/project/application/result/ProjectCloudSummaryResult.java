package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

public record ProjectCloudSummaryResult(
        boolean configured,
        Long cloudConnectionId,
        String provider,
        String displayName,
        String region,
        String status,
        LocalDateTime lastCheckedAt
) {
}
