package com.example.dvely.webhook.infrastructure.worker;

import com.example.dvely.webhook.application.WebhookService;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.lang.management.ManagementFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryWorker {

    private static final int CLAIM_BATCH_SIZE = 10;

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookService webhookService;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-webhook";

    @Scheduled(fixedDelayString = "${qeploy.webhook.worker.poll-interval-ms:1000}")
    public void dispatchPendingDeliveries() {
        webhookDeliveryRepository.recoverExpiredLeases();
        for (String deliveryId : webhookDeliveryRepository.claimPending(workerId, CLAIM_BATCH_SIZE)) {
            log.info("webhook delivery 처리: deliveryId={} workerId={}", deliveryId, workerId);
            webhookService.processDelivery(deliveryId);
        }
    }
}
