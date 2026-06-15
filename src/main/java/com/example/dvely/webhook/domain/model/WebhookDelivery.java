package com.example.dvely.webhook.domain.model;

import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

public class WebhookDelivery {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final String id;
    private final String eventType;
    private final byte[] payload;
    private WebhookDeliveryStatus status;
    private int attempt;
    private final int maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String errorMessage;
    private final LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private final LocalDateTime updatedAt;

    public WebhookDelivery(String id, String eventType, byte[] payload) {
        this(
                id,
                eventType,
                payload,
                WebhookDeliveryStatus.PENDING,
                0,
                DEFAULT_MAX_ATTEMPTS,
                LocalDateTime.now(),
                null,
                null,
                null,
                LocalDateTime.now(),
                null,
                null
        );
    }

    public WebhookDelivery(String id,
                           String eventType,
                           byte[] payload,
                           WebhookDeliveryStatus status,
                           int attempt,
                           int maxAttempts,
                           LocalDateTime nextAttemptAt,
                           String leaseOwner,
                           LocalDateTime leaseUntil,
                           String errorMessage,
                           LocalDateTime receivedAt,
                           LocalDateTime processedAt,
                           LocalDateTime updatedAt) {
        this.id = requireText(id, "id");
        this.eventType = requireText(eventType, "eventType");
        this.payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload must not be null"), payload.length);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.errorMessage = errorMessage;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
        this.updatedAt = updatedAt;
    }

    public void complete(LocalDateTime processedAt) {
        this.status = WebhookDeliveryStatus.COMPLETED;
        this.processedAt = Objects.requireNonNull(processedAt);
        this.errorMessage = null;
        clearQueue();
    }

    public void ignore(LocalDateTime processedAt) {
        this.status = WebhookDeliveryStatus.IGNORED;
        this.processedAt = Objects.requireNonNull(processedAt);
        this.errorMessage = null;
        clearQueue();
    }

    public void retry(String errorMessage, LocalDateTime now) {
        this.errorMessage = requireText(errorMessage, "errorMessage");
        if (attempt >= maxAttempts) {
            this.status = WebhookDeliveryStatus.FAILED;
            this.processedAt = now;
            clearQueue();
            return;
        }
        this.status = WebhookDeliveryStatus.RETRY_WAIT;
        this.nextAttemptAt = now.plus(retryDelay());
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public void recoverExpiredLease(LocalDateTime now) {
        this.status = WebhookDeliveryStatus.RETRY_WAIT;
        this.errorMessage = "worker lease가 만료되어 webhook 처리를 다시 시도합니다.";
        this.nextAttemptAt = now;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    private Duration retryDelay() {
        long seconds = Math.min(300, 5L << Math.max(0, attempt - 1));
        return Duration.ofSeconds(seconds);
    }

    private void clearQueue() {
        this.nextAttemptAt = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public String getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
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
