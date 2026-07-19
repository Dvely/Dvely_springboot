package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

/**
 * Response shape for both GET and PUT {@code .../settings/infrastructure/configuration}
 * (design §3.1/§3.2 — PUT returns the same shape as GET). {@code settings}/{@code pendingChange}
 * are nested records (not shared with {@link ProjectInfrastructureChangeResult}, which is the
 * history list's row shape) because the "pending" view intentionally omits status/actorUserId/
 * decidedAt — those are always PENDING_APPROVAL/unset while a change is still pending, so
 * repeating them here would be redundant noise rather than useful data.
 */
public record ProjectInfrastructureConfigurationResult(
        Long projectId,
        boolean configurable,
        Settings settings,
        PendingChange pendingChange
) {

    public record Settings(
            String deploymentArchitecture,
            String computeTier,
            String storageType,
            String networkAccess,
            LocalDateTime updatedAt
    ) {
    }

    public record PendingChange(
            Long changeId,
            Long approvalId,
            String action,
            String deploymentArchitecture,
            String computeTier,
            String storageType,
            String networkAccess,
            LocalDateTime createdAt
    ) {
    }
}
