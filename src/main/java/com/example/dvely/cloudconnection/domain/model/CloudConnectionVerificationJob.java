package com.example.dvely.cloudconnection.domain.model;

import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public class CloudConnectionVerificationJob {

    private final String id;
    private final Long cloudConnectionId;
    private final Long ownerUserId;
    private CloudConnectionVerificationJobStatus status;
    private CloudConnectionStatus connectionStatus;
    private String message;
    private int attempt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private final LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private final LocalDateTime updatedAt;

    public CloudConnectionVerificationJob(Long cloudConnectionId, Long ownerUserId) {
        this(
                UUID.randomUUID().toString(),
                cloudConnectionId,
                ownerUserId,
                CloudConnectionVerificationJobStatus.PENDING,
                CloudConnectionStatus.VALIDATED,
                "클라우드 권한 확인을 기다리고 있습니다.",
                0,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public CloudConnectionVerificationJob(String id,
                                          Long cloudConnectionId,
                                          Long ownerUserId,
                                          CloudConnectionVerificationJobStatus status,
                                          CloudConnectionStatus connectionStatus,
                                          String message,
                                          int attempt,
                                          String leaseOwner,
                                          LocalDateTime leaseUntil,
                                          LocalDateTime createdAt,
                                          LocalDateTime startedAt,
                                          LocalDateTime completedAt,
                                          LocalDateTime updatedAt) {
        this.id = requireText(id, "id");
        this.cloudConnectionId = Objects.requireNonNull(cloudConnectionId, "cloudConnectionId must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.connectionStatus = Objects.requireNonNull(connectionStatus, "connectionStatus must not be null");
        this.message = requireText(message, "message");
        this.attempt = attempt;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt;
    }

    public void complete(CloudConnectionStatus connectionStatus, String message, LocalDateTime completedAt) {
        this.status = CloudConnectionVerificationJobStatus.SUCCEEDED;
        this.connectionStatus = Objects.requireNonNull(connectionStatus, "connectionStatus must not be null");
        this.message = requireText(message, "message");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        clearLease();
    }

    public void fail(String message, LocalDateTime completedAt) {
        this.status = CloudConnectionVerificationJobStatus.FAILED;
        this.connectionStatus = CloudConnectionStatus.UNKNOWN_ERROR;
        this.message = requireText(message, "message");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        clearLease();
    }

    public void retryAfterExpiredLease() {
        this.status = CloudConnectionVerificationJobStatus.PENDING;
        this.connectionStatus = CloudConnectionStatus.VERIFYING;
        this.message = "worker lease가 만료되어 클라우드 권한 확인을 다시 시도합니다.";
        this.startedAt = null;
        clearLease();
    }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public String getId() {
        return id;
    }

    public Long getCloudConnectionId() {
        return cloudConnectionId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public CloudConnectionVerificationJobStatus getStatus() {
        return status;
    }

    public CloudConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public String getMessage() {
        return message;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
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
}
