package com.example.dvely.agent.infrastructure.docker;

/**
 * One-shot memory/CPU snapshot for a running container, as returned by
 * {@link DockerContainerService#getContainerStats(String)}. Values are raw (unrounded) —
 * percentage rounding for display is the presentation layer's concern
 * (see {@code PreviewContainerStatusResult}), not this infrastructure-level adapter's.
 *
 * @param memoryUsageBytes current cgroup memory usage, straight from Docker stats
 * @param memoryLimitBytes cgroup memory limit; expected to equal the isolation policy's
 *                         configured memory limit (1 GiB) since preview containers don't
 *                         override it, but read from the live stats response rather than the
 *                         constant so this stays correct if the policy ever changes per-container
 * @param cpuPercent       CPU usage percent computed from the cpu/precpu delta
 *                         (see {@link DockerContainerService} for the formula and its
 *                         first-sample guard); 0.0 when it can't be computed
 */
public record ContainerResourceUsage(
        long memoryUsageBytes,
        long memoryLimitBytes,
        double cpuPercent
) {
}
