package com.example.dvely.audit.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Design §8/§11 — real-DB sweep behavior. Rows are seeded directly via {@link JdbcTemplate} rather
 * than through {@code AuditLogRepository#save} because {@code created_at} is a
 * {@code @CreationTimestamp} column (always "now" through the domain path) and this test needs to
 * plant rows at specific ages relative to the retention cutoff.
 */
@SpringBootTest
class AuditLogRetentionSchedulerTest {

    private static final int DEFAULT_RETENTION_DAYS = 180;

    @Autowired
    private AuditLogRetentionScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rowsOlderThanRetentionAreDeletedAndNewerRowsSurvive() {
        long survivorId = insertRow(LocalDateTime.now().minusDays(DEFAULT_RETENTION_DAYS - 1));
        long expiredId = insertRow(LocalDateTime.now().minusDays(DEFAULT_RETENTION_DAYS + 1));

        scheduler.purgeExpiredAuditLogs();

        assertThat(rowExists(survivorId)).isTrue();
        assertThat(rowExists(expiredId)).isFalse();
    }

    @Test
    void sweepCompletesAcrossMultipleBatchesWhenMoreThan500RowsAreExpired() {
        int seedCount = 501;
        LocalDateTime expiredAt = LocalDateTime.now().minusDays(DEFAULT_RETENTION_DAYS + 1);
        List<Long> seededIds = new ArrayList<>();
        for (int i = 0; i < seedCount; i++) {
            seededIds.add(insertRow(expiredAt));
        }

        scheduler.purgeExpiredAuditLogs();

        long remaining = seededIds.stream().filter(this::rowExists).count();
        assertThat(remaining).isZero();
    }

    private long insertRow(LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                        insert into audit_logs
                            (category, action, outcome, actor_type, project_id, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                "DEPLOYMENT", "DEPLOYMENT_REQUESTED", "SUCCEEDED", "USER", 999_000_000L,
                createdAt.truncatedTo(ChronoUnit.SECONDS)
        );
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
    }

    private boolean rowExists(long auditLogId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where audit_log_id = ?", Integer.class, auditLogId);
        return count != null && count > 0;
    }
}
