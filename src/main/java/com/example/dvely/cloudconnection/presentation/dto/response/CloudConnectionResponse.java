package com.example.dvely.cloudconnection.presentation.dto.response;

import java.time.LocalDateTime;

public record CloudConnectionResponse(
        Long cloudConnectionId,
        String provider,
        String displayName,
        String accountId,
        String region,
        String roleArn,
        String awsCredentialType,
        String accessKeyId,
        boolean secretAccessKeyConfigured,
        boolean sessionTokenConfigured,
        String gcpCredentialType,
        boolean serviceAccountKeyConfigured,
        String projectId,
        String serviceAccountEmail,
        String status,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
