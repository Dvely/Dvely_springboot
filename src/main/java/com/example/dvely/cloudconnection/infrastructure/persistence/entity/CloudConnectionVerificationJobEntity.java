package com.example.dvely.cloudconnection.infrastructure.persistence.entity;

import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "cloud_connection_verification_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CloudConnectionVerificationJobEntity {

    @Id
    @Column(name = "job_id", length = 36)
    private String id;

    @Column(name = "cloud_connection_id", nullable = false)
    private Long cloudConnectionId;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "connection_status", nullable = false)
    private String connectionStatus;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private CloudConnectionVerificationJobEntity(CloudConnectionVerificationJob job) {
        this.id = job.getId();
        updateFrom(job);
    }

    public static CloudConnectionVerificationJobEntity from(CloudConnectionVerificationJob job) {
        return new CloudConnectionVerificationJobEntity(job);
    }

    public void updateFrom(CloudConnectionVerificationJob job) {
        this.cloudConnectionId = job.getCloudConnectionId();
        this.ownerUserId = job.getOwnerUserId();
        this.status = job.getStatus().name();
        this.connectionStatus = job.getConnectionStatus().name();
        this.message = job.getMessage();
        this.attempt = job.getAttempt();
        this.leaseOwner = job.getLeaseOwner();
        this.leaseUntil = job.getLeaseUntil();
        this.startedAt = job.getStartedAt();
        this.completedAt = job.getCompletedAt();
    }

    public CloudConnectionVerificationJob toDomain() {
        return new CloudConnectionVerificationJob(
                id,
                cloudConnectionId,
                ownerUserId,
                CloudConnectionVerificationJobStatus.valueOf(status),
                CloudConnectionStatus.valueOf(connectionStatus),
                message,
                attempt,
                leaseOwner,
                leaseUntil,
                createdAt,
                startedAt,
                completedAt,
                updatedAt
        );
    }
}
