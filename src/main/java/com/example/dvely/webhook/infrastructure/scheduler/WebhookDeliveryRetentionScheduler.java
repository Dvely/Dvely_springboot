package com.example.dvely.webhook.infrastructure.scheduler;

import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code webhook_deliveries} 보존 스윕(#338).
 *
 * <p>이 테이블에는 삭제 로직이 하나도 없었다. push · workflow_run 이벤트마다 한 행이 들어오고
 * 각 행이 {@code payload LONGBLOB NOT NULL} 을 들고 있으므로, 저장소가 사실상 무한히 커진다.
 * 이 배달들은 멱등 처리와 재시도를 위해 존재하는 것이라 최종 상태에 도달하고 며칠 지나면 아무도
 * 읽지 않는다.</p>
 *
 * <p>{@link com.example.dvely.audit.infrastructure.scheduler.AuditLogRetentionScheduler} 의
 * 형태를 그대로 따른다 — 고정 지연 주기, 프로퍼티로 받는 보존 기간, 그리고 한 문장으로 전부
 * 지우는 대신 LIMIT 배치를 0 이 나올 때까지 반복하는 삭제. payload 가 LONGBLOB 이라 한 번에
 * 대량 삭제하면 그동안 행 잠금을 쥐고 undo 로그가 커진다.</p>
 *
 * <p><b>터미널 상태만 지운다.</b> 처리 대기·재시도 대기·처리 중인 배달을 지우면 그 GitHub
 * 이벤트가 그대로 유실된다 — 어떤 상태가 터미널인지는
 * {@code WebhookDeliveryRepositoryAdapter.TERMINAL_STATUSES} 가 정한다.</p>
 */
@Slf4j
@Component
public class WebhookDeliveryRetentionScheduler {

    /** AuditLogRetentionScheduler 와 같은 "문장당 500 행" 전략. */
    private static final int BATCH_SIZE = 500;

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final long retentionDays;

    public WebhookDeliveryRetentionScheduler(
            WebhookDeliveryRepository webhookDeliveryRepository,
            @Value("${qeploy.webhook.retention-days:7}") long retentionDays) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${qeploy.webhook.retention-sweep-interval-ms:3600000}")
    public void purgeTerminalDeliveries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int deletedInBatch;
        do {
            deletedInBatch = webhookDeliveryRepository.deleteTerminalBatch(cutoff, BATCH_SIZE);
            totalDeleted += deletedInBatch;
        } while (deletedInBatch > 0);
        if (totalDeleted > 0) {
            log.info("웹훅 배달 retention 삭제: count={} retentionDays={}", totalDeleted, retentionDays);
        }
    }
}
