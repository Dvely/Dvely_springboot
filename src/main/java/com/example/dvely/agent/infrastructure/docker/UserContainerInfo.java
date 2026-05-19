package com.example.dvely.agent.infrastructure.docker;

import java.time.Duration;
import java.time.Instant;

public record UserContainerInfo(
        String  containerId,
        int     previewPort,
        Instant lastAccessedAt
) {
    public UserContainerInfo touch() {
        return new UserContainerInfo(containerId, previewPort, Instant.now());
    }

    public boolean isExpired(Duration ttl) {
        return Instant.now().isAfter(lastAccessedAt.plus(ttl));
    }
}
