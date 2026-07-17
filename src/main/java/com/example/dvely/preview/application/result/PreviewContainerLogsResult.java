package com.example.dvely.preview.application.result;

/**
 * @param logText stdout+stderr merged, each line prefixed by Docker's own timestamp
 *                (see {@code DockerContainerService#getContainerLogs}); empty string (not null)
 *                when the container has already been removed — the session itself still exists.
 */
public record PreviewContainerLogsResult(
        String sessionId,
        boolean containerRunning,
        String logText
) {
}
