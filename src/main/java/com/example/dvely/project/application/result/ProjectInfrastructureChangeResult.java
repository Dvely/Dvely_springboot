package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

/** One row of the infrastructure settings change history (design §3.3, BI-129). */
public record ProjectInfrastructureChangeResult(
        Long changeId,
        String action,
        String status,
        String deploymentArchitecture,
        String computeTier,
        String storageType,
        String networkAccess,
        Long approvalId,
        Long actorUserId,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
