package com.example.dvely.cloudconnection.domain.model;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import java.time.LocalDateTime;
import java.util.Objects;

public class CloudConnection {

    private final Long id;
    private final Long ownerUserId;
    private final CloudProvider provider;
    private String displayName;
    private String accountId;
    private String region;
    private String roleArn;
    private String awsCredentialType;
    private String accessKeyId;
    private String secretAccessKey;
    private String sessionToken;
    private String gcpCredentialType;
    private String serviceAccountKeyJson;
    private String gcpProjectId;
    private String serviceAccountEmail;
    private CloudConnectionStatus status;
    private LocalDateTime lastCheckedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public CloudConnection(Long ownerUserId,
                           CloudProvider provider,
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
                           String serviceAccountEmail) {
        this(
                null,
                ownerUserId,
                provider,
                displayName,
                accountId,
                region,
                roleArn,
                awsCredentialType,
                accessKeyId,
                secretAccessKey,
                sessionToken,
                gcpCredentialType,
                serviceAccountKeyJson,
                gcpProjectId,
                serviceAccountEmail,
                CloudConnectionStatus.VALIDATED,
                null,
                null,
                null
        );
    }

    public CloudConnection(Long id,
                           Long ownerUserId,
                           CloudProvider provider,
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
                           String serviceAccountEmail,
                           CloudConnectionStatus status,
                           LocalDateTime lastCheckedAt,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
        this.id = id;
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.displayName = requireText(displayName, "displayName");
        this.accountId = trimToNull(accountId);
        this.region = requireText(region, "region").toLowerCase();
        this.roleArn = trimToNull(roleArn);
        this.awsCredentialType = trimToNull(awsCredentialType);
        this.accessKeyId = trimToNull(accessKeyId);
        this.secretAccessKey = trimToNull(secretAccessKey);
        this.sessionToken = trimToNull(sessionToken);
        this.gcpCredentialType = trimToNull(gcpCredentialType);
        this.serviceAccountKeyJson = trimToNull(serviceAccountKeyJson);
        this.gcpProjectId = trimToNull(gcpProjectId);
        this.serviceAccountEmail = trimToNull(serviceAccountEmail);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.lastCheckedAt = lastCheckedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void markHealth(CloudConnectionStatus status, LocalDateTime checkedAt) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.lastCheckedAt = Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }

    public void markValidated() {
        this.status = CloudConnectionStatus.VALIDATED;
    }

    public void markVerifying() {
        this.status = CloudConnectionStatus.VERIFYING;
    }

    /**
     * 같은 연결(id·리전)에 AWS 자격만 새로 넣는다. 자원은 연결 id 에 묶여 있어서, 자격이 죽은 연결을
     * 지우고 새로 만들면 그 연결로 만든 DB·서버·CDN 을 다시는 다룰 수 없다(#491). 세션 토큰은 항상
     * 비운다 — 임시 자격은 받지 않으므로 남아 있으면 옛 토큰이 새 키에 붙는다.
     */
    public void replaceAwsCredentials(String awsCredentialType,
                                      String accountId,
                                      String roleArn,
                                      String accessKeyId,
                                      String secretAccessKey) {
        if (provider != CloudProvider.AWS) {
            throw new IllegalStateException("AWS 연결만 자격을 교체할 수 있습니다.");
        }
        this.awsCredentialType = requireText(awsCredentialType, "awsCredentialType");
        this.accountId = trimToNull(accountId);
        this.roleArn = trimToNull(roleArn);
        this.accessKeyId = trimToNull(accessKeyId);
        this.secretAccessKey = trimToNull(secretAccessKey);
        this.sessionToken = null;
        this.status = CloudConnectionStatus.VALIDATED;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public CloudProvider getProvider() {
        return provider;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRegion() {
        return region;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getAwsCredentialType() {
        return awsCredentialType;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getGcpCredentialType() {
        return gcpCredentialType;
    }

    public String getServiceAccountKeyJson() {
        return serviceAccountKeyJson;
    }

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public String getServiceAccountEmail() {
        return serviceAccountEmail;
    }

    public CloudConnectionStatus getStatus() {
        return status;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
