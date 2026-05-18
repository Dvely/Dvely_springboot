package com.example.dvely.cloudconnection.presentation.dto.response;

import java.time.LocalDateTime;

public record CloudConnectionHealthResponse(
        Long cloudConnectionId,
        String provider,
        String status,
        String message,
        LocalDateTime checkedAt
) {
}
