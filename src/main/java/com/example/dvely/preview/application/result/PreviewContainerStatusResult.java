package com.example.dvely.preview.application.result;

import com.example.dvely.agent.infrastructure.docker.ContainerResourceUsage;
import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;

/**
 * Composed view of a preview session's DB row and its Docker container state, as returned by
 * {@code PreviewContainerOpsService#getStatus}. {@link #of} is the single place that derives the
 * rounded, display-ready {@code resources} percentages from the raw
 * {@link ContainerResourceUsage} sample — keeping that math out of both the Docker adapter
 * (which shouldn't know about display formatting) and the presentation layer (which, per the
 * rest of this codebase's Response DTOs, just passes Result fields through 1:1).
 */
public record PreviewContainerStatusResult(
        String sessionId,
        Long projectId,
        String taskId,
        String sessionStatus,
        boolean containerRunning,
        Boolean oomKilled,
        Long exitCode,
        LocalDateTime startedAt,
        LocalDateTime expiresAt,
        ResourceUsageResult resources
) {
    public record ResourceUsageResult(
            long memoryUsageBytes,
            long memoryLimitBytes,
            double memoryUsagePercent,
            double cpuPercent
    ) {
    }

    public static PreviewContainerStatusResult of(
            PreviewSessionEntity session,
            ContainerRuntimeStatus runtimeStatus,
            ContainerResourceUsage usage
    ) {
        return new PreviewContainerStatusResult(
                session.getId(),
                session.getProjectId(),
                session.getTaskId(),
                session.getStatus(),
                runtimeStatus.running(),
                runtimeStatus.oomKilled(),
                runtimeStatus.exitCode(),
                runtimeStatus.startedAt(),
                session.getExpiresAt(),
                toResourceUsageResult(usage)
        );
    }

    private static ResourceUsageResult toResourceUsageResult(ContainerResourceUsage usage) {
        if (usage == null) {
            return null;
        }
        // memoryLimitBytes <= 0 would only happen if Docker's stats response omitted the field
        // entirely — guard against a division by zero rather than propagate NaN/Infinity.
        double memoryUsagePercent = usage.memoryLimitBytes() > 0
                ? round1((double) usage.memoryUsageBytes() / usage.memoryLimitBytes() * 100)
                : 0.0;
        return new ResourceUsageResult(
                usage.memoryUsageBytes(),
                usage.memoryLimitBytes(),
                memoryUsagePercent,
                round1(usage.cpuPercent())
        );
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
