package com.example.dvely.project.application.result;

import java.time.OffsetDateTime;

public record ActivityLogResult(
        String type,
        String message,
        OffsetDateTime occurredAt
) {
}
