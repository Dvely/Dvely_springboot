package com.example.dvely.project.presentation.dto.response;

import java.time.OffsetDateTime;

public record ProjectActivityLogResponse(
        String type,
        String message,
        OffsetDateTime occurredAt
) {
}
