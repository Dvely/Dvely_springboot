package com.example.dvely.cloudconnection.application.command.dto;

public record CreateCloudConnectionCommand(
        String provider,
        String displayName,
        String accountId,
        String region,
        String roleArn,
        String awsCredentialType,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        String gcpCredentialType,
        String serviceAccountKeyJson,
        String gcpProjectId,
        String serviceAccountEmail
) {
}
