package com.example.dvely.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #70 (QA v2 §3, Critical) — schema-level guard mirroring {@code CloudConnectionSchemaTest}
 * / {@code EnvironmentVariableSchemaTest}: proves V29 actually reached the database rather than
 * merely existing as a file, and that {@code next_attempt_at} is nullable in the running schema.
 * <p>
 * V21 originally declared {@code next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP}, but
 * {@code SpringDataWebhookDeliveryRepository#claim} intentionally sets it to {@code null} to mark a
 * row as "currently being processed, not queued for a future attempt" (see
 * {@link WebhookDeliveryClaimIntegrationTest} for the behavioral proof). That NOT NULL constraint
 * made every real claim() throw {@code SQLIntegrityConstraintViolationException}, permanently
 * halting the webhook worker in production. Hibernate's {@code ddl-auto: validate} does not check
 * column nullability, so nothing short of a real-schema assertion like this one would have caught
 * the mismatch between the (already-nullable) entity mapping and the (NOT NULL) column.
 */
@SpringBootTest
class WebhookDeliverySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v29MigrationAppliedSuccessfully() {
        String v29Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '29'
                        """,
                String.class
        );

        assertTrue("1".equals(v29Applied) || "true".equalsIgnoreCase(v29Applied));
    }

    @Test
    void nextAttemptAtColumnIsNullable() {
        String isNullable = jdbcTemplate.queryForObject(
                """
                        select is_nullable
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'webhook_deliveries'
                          and column_name = 'next_attempt_at'
                        """,
                String.class
        );

        assertEquals("YES", isNullable);
    }
}
