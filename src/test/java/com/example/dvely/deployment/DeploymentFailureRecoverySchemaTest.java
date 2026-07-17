package com.example.dvely.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for V24 (mirrors CloudConnectionSchemaTest/EnvironmentVariableSchemaTest):
 * Hibernate's {@code ddl-auto: validate} confirms columns exist with a compatible type, but
 * doesn't check unique constraints — the analysis race-guard (design §3.4) depends entirely on
 * {@code uk_deployment_failure_analyses_history} actually existing, so it needs its own check.
 */
@SpringBootTest
class DeploymentFailureRecoverySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v24MigrationAppliedSuccessfully() {
        String v24Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '24'
                        """,
                String.class
        );

        assertTrue("1".equals(v24Applied) || "true".equalsIgnoreCase(v24Applied));
    }

    @Test
    void logExcerptColumnIsMediumtext() {
        String columnType = jdbcTemplate.queryForObject(
                """
                        select column_type
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'deployment_failure_analyses'
                          and column_name = 'log_excerpt'
                        """,
                String.class
        );

        assertEquals("mediumtext", columnType);
    }

    @Test
    void uniqueConstraintOnHistoryIdExists() {
        Long indexCount = jdbcTemplate.queryForObject(
                """
                        select count(distinct index_name)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'deployment_failure_analyses'
                          and index_name = 'uk_deployment_failure_analyses_history'
                        """,
                Long.class
        );

        assertEquals(1L, indexCount);
    }

    @Test
    void retriedFromHistoryIdColumnExistsOnDeploymentHistories() {
        Long columnCount = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'deployment_histories'
                          and column_name = 'retried_from_history_id'
                        """,
                Long.class
        );

        assertEquals(1L, columnCount);
    }
}
