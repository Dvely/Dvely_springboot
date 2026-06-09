package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.TaskStatus;
import java.time.LocalDateTime;

public record AgentTaskEventResponse(
        Long eventId,
        String taskId,
        String type,
        TaskStatus status,
        String message,
        LocalDateTime createdAt
) {
}
