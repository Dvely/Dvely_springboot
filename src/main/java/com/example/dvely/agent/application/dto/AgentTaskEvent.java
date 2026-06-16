package com.example.dvely.agent.application.dto;

import java.time.LocalDateTime;

public record AgentTaskEvent(
        Long eventId,
        String taskId,
        String type,
        TaskStatus status,
        String message,
        LocalDateTime createdAt
) {
}
