package com.example.dvely.webhook.domain.repository;

import com.example.dvely.webhook.domain.model.WebhookDelivery;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryRepository {

    boolean enqueue(WebhookDelivery delivery);

    Optional<WebhookDelivery> findById(String deliveryId);

    WebhookDelivery save(WebhookDelivery delivery);

    List<String> claimPending(String workerId, int limit);

    void recoverExpiredLeases();
}
