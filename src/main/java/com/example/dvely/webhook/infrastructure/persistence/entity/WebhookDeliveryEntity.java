package com.example.dvely.webhook.infrastructure.persistence.entity;

import com.example.dvely.webhook.domain.model.WebhookDelivery;
import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDeliveryEntity {

    @Id
    @Column(name = "delivery_id", length = 64)
    private String id;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] payload;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    // Nullable by design (V29, issue #70): only meaningful while the delivery is queued
    // (PENDING/RETRY_WAIT). SpringDataWebhookDeliveryRepository#claim clears it to null the
    // moment a delivery moves to PROCESSING, and WebhookDelivery#complete()/ignore() do the same
    // on terminal states — a NOT NULL column (V21's original definition) made every claim() throw
    // a constraint violation and permanently stalled the worker.
    @Column(name = "next_attempt_at", nullable = true)
    private LocalDateTime nextAttemptAt;

    @Column(name = "lease_owner", length = 120)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static WebhookDeliveryEntity from(WebhookDelivery delivery) {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.id = delivery.getId();
        entity.eventType = delivery.getEventType();
        entity.payload = delivery.getPayload();
        entity.receivedAt = delivery.getReceivedAt();
        entity.updateFrom(delivery);
        return entity;
    }

    public void updateFrom(WebhookDelivery delivery) {
        this.status = delivery.getStatus().name();
        this.attempt = delivery.getAttempt();
        this.maxAttempts = delivery.getMaxAttempts();
        this.nextAttemptAt = delivery.getNextAttemptAt();
        this.leaseOwner = delivery.getLeaseOwner();
        this.leaseUntil = delivery.getLeaseUntil();
        this.errorMessage = delivery.getErrorMessage();
        this.processedAt = delivery.getProcessedAt();
    }

    public WebhookDelivery toDomain() {
        return new WebhookDelivery(
                id,
                eventType,
                payload,
                WebhookDeliveryStatus.valueOf(status),
                attempt,
                maxAttempts,
                nextAttemptAt,
                leaseOwner,
                leaseUntil,
                errorMessage,
                receivedAt,
                processedAt,
                updatedAt
        );
    }
}
