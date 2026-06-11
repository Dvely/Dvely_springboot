package com.example.dvely.cloudconnection.application.result;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import java.time.LocalDateTime;

public record CloudConnectionVerificationJobResult(
        String jobId,
        Long cloudConnectionId,
        CloudConnectionVerificationJobStatus status,
        CloudConnectionStatus connectionStatus,
        String message,
        int attempt,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
