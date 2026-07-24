package com.example.dvely.audit.infrastructure.scheduler;

import com.example.dvely.audit.domain.repository.AuditLogRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic {@code audit_logs} retention sweep (design §8, ADR-A7 — 180-day default, PRD-unbacked
 * proposal per the design doc). Mirrors {@code ChatTrashCleanupScheduler}'s shape (fixed-delay,
 * property-driven interval) with one difference: each sweep deletes in bounded batches rather than
 * one statement, because a single {@code DELETE ... WHERE created_at < ?} against a table that
 * accumulates for 180 days could touch far more rows at once than the webhook/chat cleanups ever
 * would, holding row locks and growing the undo log for the whole duration.
 */
@Slf4j
@Component
public class AuditLogRetentionScheduler {

    /** Matches the design's "500 rows per statement" repeated-delete strategy (§8). */
    private static final int BATCH_SIZE = 500;

    private final AuditLogRepository auditLogRepository;
    private final long retentionDays;

    public AuditLogRetentionScheduler(AuditLogRepository auditLogRepository,
                                      @Value("${qeploy.audit.retention-days:180}") long retentionDays) {
        this.auditLogRepository = auditLogRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${qeploy.audit.retention-sweep-interval-ms:3600000}")
    public void purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int deletedInBatch;
        do {
            deletedInBatch = auditLogRepository.deleteBatch(cutoff, BATCH_SIZE);
            totalDeleted += deletedInBatch;
        } while (deletedInBatch > 0);
        if (totalDeleted > 0) {
            log.info("감사 로그 retention 삭제: count={} retentionDays={}", totalDeleted, retentionDays);
        }
    }
}
