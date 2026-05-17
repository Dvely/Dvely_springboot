package com.example.dvely.cloudconnection.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCloudConnectionRequest(
        @NotBlank String provider,
        @NotBlank @Size(max = 100) String displayName,
        String accountId,
        @NotBlank String region,
        String roleArn,
        String awsCredentialType,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        String gcpCredentialType,
        String serviceAccountKeyJson,
        String projectId,
        String serviceAccountEmail
) {
}
