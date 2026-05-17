package com.example.dvely.cloudconnection.presentation.dto.response;

public record CreateCloudConnectionResponse(
        Long cloudConnectionId,
        String provider,
        String status,
        String jobId
) {
}
