package com.example.dvely.cloudconnection.application.result;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import java.time.LocalDateTime;

public record CloudConnectionResult(
        Long cloudConnectionId,
        CloudProvider provider,
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
        String gcpProjectId,
        String serviceAccountEmail,
        CloudConnectionStatus status,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
