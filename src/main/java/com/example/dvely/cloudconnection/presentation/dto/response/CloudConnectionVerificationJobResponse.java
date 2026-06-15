package com.example.dvely.cloudconnection.presentation.dto.response;

import java.time.LocalDateTime;

public record CloudConnectionVerificationJobResponse(
        String jobId,
        Long cloudConnectionId,
        String status,
        String connectionStatus,
        String message,
        int attempt,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
