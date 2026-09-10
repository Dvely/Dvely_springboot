package com.example.dvely.webhook.infrastructure.worker;

import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkerPollGate;
import com.example.dvely.webhook.application.WebhookService;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.lang.management.ManagementFactory;
import java.util.List;
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
    private final WorkerPollGate pollGate;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-webhook";

    @Scheduled(fixedDelayString = "${qeploy.webhook.worker.poll-interval-ms:1000}")
    public void dispatchPendingDeliveries() {
        // #340 5-1: 틱은 1초마다 오지만, 일이 없는 동안에는 DB 를 치지 않는다. 새 배달이 들어오면
        // enqueue 커밋 뒤에 오는 깨우기 신호가 백오프를 즉시 풀어, 다음 틱(≤1초)에 집힌다.
        if (!pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY)) {
            return;
        }
        List<String> deliveryIds;
        try {
            deliveryIds = webhookDeliveryRepository.recoverAndClaimPending(workerId, CLAIM_BATCH_SIZE);
        } catch (RuntimeException exception) {
            pollGate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);
            throw exception;
        }
        if (deliveryIds.isEmpty()) {
            pollGate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);
        } else {
            pollGate.recordBusy(WorkQueue.WEBHOOK_DELIVERY);
        }
        for (String deliveryId : deliveryIds) {
            log.info("webhook delivery 처리: deliveryId={} workerId={}", deliveryId, workerId);
            webhookService.processDelivery(deliveryId);
        }
    }
}
