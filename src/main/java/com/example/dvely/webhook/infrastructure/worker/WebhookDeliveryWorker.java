package com.example.dvely.webhook.infrastructure.worker;

import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkerPollGate;
import com.example.dvely.webhook.application.WebhookService;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING·RETRY_WAIT 웹훅 배달을 집어 전용 executor 로 넘긴다.
 *
 * <p>#340 5-3: 예전에는 배달 처리를 <b>스케줄러 스레드에서 동기로</b> 돌렸다. 핸들러가 GitHub API
 * 를 호출하므로 한 배달이 느리면 그 동안 이 워커의 다음 폴링이 통째로 밀리고, 스케줄러 풀을
 * 공유하는 다른 잡까지 함께 굶었다. 한 폴링이 최대 10건을 직렬로 처리했으므로 최악은 그 10배다.</p>
 *
 * <p>비동기로 넘기면 {@code TaskRejectedException} 이라는 새 실패 경로가 생긴다 — 그래서 배달
 * 하나씩 격리하고, 거부되면 claim 을 즉시 PENDING 으로 되돌린다({@code DeploymentRunWorker} ·
 * {@code AgentRunWorker} 와 같은 ADR-Y3 패턴). 되돌리지 않으면 그 배달은 리스 만료(2분)까지
 * PROCESSING 인 채 남고, GitHub 은 우리가 200 을 이미 준 배달을 다시 보내주지 않는다.</p>
 */
@Slf4j
@Component
public class WebhookDeliveryWorker {

    private static final int CLAIM_BATCH_SIZE = 10;

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookService webhookService;
    private final WorkerPollGate pollGate;
    private final Executor webhookExecutor;
    private final long dispatchRejectBackoffMs;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-webhook";

    public WebhookDeliveryWorker(WebhookDeliveryRepository webhookDeliveryRepository,
                                 WebhookService webhookService,
                                 WorkerPollGate pollGate,
                                 @Qualifier("webhookExecutor") Executor webhookExecutor,
                                 @Value("${qeploy.webhook.worker.dispatch-reject-backoff-ms:5000}")
                                 long dispatchRejectBackoffMs) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookService = webhookService;
        this.pollGate = pollGate;
        this.webhookExecutor = webhookExecutor;
        this.dispatchRejectBackoffMs = dispatchRejectBackoffMs;
    }

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
            // DB 가 흔들리는 동안 매초 같은 쿼리를 던져봐야 소용이 없다 — 물러나며 재시도한다.
            pollGate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);
            throw exception;
        }
        if (deliveryIds.isEmpty()) {
            pollGate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);
        } else {
            pollGate.recordBusy(WorkQueue.WEBHOOK_DELIVERY);
        }
        for (String deliveryId : deliveryIds) {
            dispatchOne(deliveryId);
        }
    }

    private void dispatchOne(String deliveryId) {
        try {
            log.info("webhook delivery 처리 위임: deliveryId={} workerId={}", deliveryId, workerId);
            webhookExecutor.execute(() -> processSafely(deliveryId));
        } catch (RuntimeException exception) {   // TaskRejectedException(실행기 포화) 포함
            boolean released = webhookDeliveryRepository.releaseClaim(
                    deliveryId, workerId, dispatchRejectBackoffMs);
            log.warn("webhook delivery 위임 실패 — 재대기열로 반환합니다. deliveryId={} workerId={} released={} 원인={}",
                    deliveryId, workerId, released, exception.toString());
        }
    }

    /**
     * executor 스레드에서 도는 실제 처리. {@code processDelivery} 는 핸들러 예외를 스스로 잡아
     * RETRY_WAIT 로 적으므로 여기까지 올라오는 것은 그 바깥의 사고(배달 행이 사라졌다든지)뿐이다.
     * 그것을 삼키지 않고 남긴다 — executor 스레드에서 던지면 아무 데도 안 남고, 그 행은 리스
     * 만료까지 PROCESSING 으로 보인다.
     */
    private void processSafely(String deliveryId) {
        try {
            webhookService.processDelivery(deliveryId);
        } catch (RuntimeException exception) {
            log.error("webhook delivery 처리 중 예기치 못한 오류 — 리스 만료 후 회수됩니다. deliveryId={}",
                    deliveryId, exception);
        }
    }
}
