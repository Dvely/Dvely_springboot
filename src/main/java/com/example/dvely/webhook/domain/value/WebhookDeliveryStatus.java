package com.example.dvely.webhook.domain.value;

public enum WebhookDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    IGNORED,
    FAILED
}
