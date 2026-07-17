package com.example.dvely.environment.application.result;

import java.time.LocalDateTime;

public record EnvironmentVariableHistoryResult(
        Long historyId,
        Long environmentVariableId,
        String scope,
        String key,
        String action,
        boolean secret,
        boolean valueChanged,
        Long actorUserId,
        LocalDateTime createdAt
) {
}
