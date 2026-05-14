package com.example.dvely.domainbinding.domain.model;

import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDateTime;
import java.util.Objects;

public class DomainBinding {

    private final Long id;
    private final Long projectId;
    private final DomainType type;
    private final String hostname;
    private DomainStatus status;
    private VerificationMethod verificationMethod;
    private String dnsTarget;
    private String cloudflareRecordId;
    private LocalDateTime lastCheckedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public DomainBinding(Long projectId,
                         DomainType type,
                         String hostname,
                         DomainStatus status,
                         VerificationMethod verificationMethod,
                         String dnsTarget) {
        this(
                null,
                projectId,
                type,
                hostname,
                status,
                verificationMethod,
                dnsTarget,
                null,
                null,
                null,
                null
        );
    }

    public DomainBinding(Long id,
                         Long projectId,
                         DomainType type,
                         String hostname,
                         DomainStatus status,
                         VerificationMethod verificationMethod,
                         String dnsTarget,
                         String cloudflareRecordId,
                         LocalDateTime lastCheckedAt,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.hostname = requireText(hostname, "hostname").toLowerCase();
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.verificationMethod = verificationMethod;
        this.dnsTarget = dnsTarget;
        this.cloudflareRecordId = cloudflareRecordId;
        this.lastCheckedAt = lastCheckedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void assignCloudflareRecord(String recordId) {
        this.cloudflareRecordId = requireText(recordId, "recordId");
        this.status = DomainStatus.VERIFYING;
    }

    public void markVerificationChecked(boolean connected) {
        this.lastCheckedAt = LocalDateTime.now();
        this.status = connected ? DomainStatus.CONNECTED : DomainStatus.VERIFYING;
    }

    public void fail() {
        this.status = DomainStatus.FAILED;
        this.lastCheckedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public DomainType getType() {
        return type;
    }

    public String getHostname() {
        return hostname;
    }

    public DomainStatus getStatus() {
        return status;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public String getDnsTarget() {
        return dnsTarget;
    }

    public String getCloudflareRecordId() {
        return cloudflareRecordId;
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
}
