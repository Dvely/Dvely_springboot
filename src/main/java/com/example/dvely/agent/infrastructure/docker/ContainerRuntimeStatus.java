package com.example.dvely.agent.infrastructure.docker;

import java.time.LocalDateTime;

/**
 * Lifecycle snapshot of a single Docker container, derived from `inspect` (see
 * {@link DockerContainerService#getContainerStatus(String)}). This intentionally excludes
 * resource usage (memory/CPU) — that lives in {@link ContainerResourceUsage} because it comes
 * from a separate, slower `stats` call that the caller may skip (e.g. when the container isn't
 * running) or that may fail independently of the inspect call.
 *
 * @param running   true only when Docker reports the container as actively running
 * @param oomKilled null when the container was never started or inspect couldn't determine it;
 *                  otherwise whether the kernel OOM-killed the process (relevant once the
 *                  memory/swap isolation limits in {@link DockerContainerService} are in effect)
 * @param exitCode  null while running or when unknown; Docker's raw exit code once stopped
 * @param startedAt container start time in the JVM's system default zone, or null if the
 *                  container was never started or Docker's timestamp couldn't be parsed
 */
public record ContainerRuntimeStatus(
        boolean running,
        Boolean oomKilled,
        Long exitCode,
        LocalDateTime startedAt
) {
    /**
     * Represents "no such container" — used both when Docker returns 404 and when a session's
     * container was simply never created. Distinguishing this from a daemon communication
     * failure matters: the former is a normal "not running" response (200), the latter must
     * propagate as a 500 (see design doc D5).
     */
    public static ContainerRuntimeStatus notFound() {
        return new ContainerRuntimeStatus(false, null, null, null);
    }
}
