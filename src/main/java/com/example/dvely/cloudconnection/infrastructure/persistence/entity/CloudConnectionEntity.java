package com.example.dvely.cloudconnection.infrastructure.persistence.entity;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.auth.infrastructure.persistence.converter.AesEncryptor;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "cloud_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CloudConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cloud_connection_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "role_arn")
    private String roleArn;

    @Column(name = "aws_credential_type")
    private String awsCredentialType;

    @Column(name = "access_key_id")
    private String accessKeyId;

    @Column(name = "secret_access_key", columnDefinition = "MEDIUMTEXT")
    @Convert(converter = AesEncryptor.class)
    private String secretAccessKey;

    @Column(name = "session_token", columnDefinition = "MEDIUMTEXT")
    @Convert(converter = AesEncryptor.class)
    private String sessionToken;

    @Column(name = "gcp_credential_type")
    private String gcpCredentialType;

    @Column(name = "service_account_key_json", columnDefinition = "MEDIUMTEXT")
    @Convert(converter = AesEncryptor.class)
    private String serviceAccountKeyJson;

    @Column(name = "gcp_project_id")
    private String gcpProjectId;

    @Column(name = "service_account_email")
    private String serviceAccountEmail;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private CloudConnectionEntity(Long ownerUserId,
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
                                  String serviceAccountEmail,
                                  String status,
                                  LocalDateTime lastCheckedAt) {
        this.ownerUserId = ownerUserId;
        this.provider = provider;
        this.displayName = displayName;
        this.accountId = accountId;
        this.region = region;
        this.roleArn = roleArn;
        this.awsCredentialType = awsCredentialType;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.gcpCredentialType = gcpCredentialType;
        this.serviceAccountKeyJson = serviceAccountKeyJson;
        this.gcpProjectId = gcpProjectId;
        this.serviceAccountEmail = serviceAccountEmail;
        this.status = status;
        this.lastCheckedAt = lastCheckedAt;
    }

    public static CloudConnectionEntity from(CloudConnection cloudConnection) {
        return new CloudConnectionEntity(
                cloudConnection.getOwnerUserId(),
                cloudConnection.getProvider().name(),
                cloudConnection.getDisplayName(),
                cloudConnection.getAccountId(),
                cloudConnection.getRegion(),
                cloudConnection.getRoleArn(),
                cloudConnection.getAwsCredentialType(),
                cloudConnection.getAccessKeyId(),
                cloudConnection.getSecretAccessKey(),
                cloudConnection.getSessionToken(),
                cloudConnection.getGcpCredentialType(),
                cloudConnection.getServiceAccountKeyJson(),
                cloudConnection.getGcpProjectId(),
                cloudConnection.getServiceAccountEmail(),
                cloudConnection.getStatus().name(),
                cloudConnection.getLastCheckedAt()
        );
    }

    public void updateFrom(CloudConnection cloudConnection) {
        this.ownerUserId = cloudConnection.getOwnerUserId();
        this.provider = cloudConnection.getProvider().name();
        this.displayName = cloudConnection.getDisplayName();
        this.accountId = cloudConnection.getAccountId();
        this.region = cloudConnection.getRegion();
        this.roleArn = cloudConnection.getRoleArn();
        this.awsCredentialType = cloudConnection.getAwsCredentialType();
        this.accessKeyId = cloudConnection.getAccessKeyId();
        this.secretAccessKey = cloudConnection.getSecretAccessKey();
        this.sessionToken = cloudConnection.getSessionToken();
        this.gcpCredentialType = cloudConnection.getGcpCredentialType();
        this.serviceAccountKeyJson = cloudConnection.getServiceAccountKeyJson();
        this.gcpProjectId = cloudConnection.getGcpProjectId();
        this.serviceAccountEmail = cloudConnection.getServiceAccountEmail();
        this.status = cloudConnection.getStatus().name();
        this.lastCheckedAt = cloudConnection.getLastCheckedAt();
    }

    public CloudConnection toDomain() {
        return new CloudConnection(
                id,
                ownerUserId,
                CloudProvider.valueOf(provider),
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
                CloudConnectionStatus.valueOf(status),
                lastCheckedAt,
                createdAt,
                updatedAt
        );
    }
}
