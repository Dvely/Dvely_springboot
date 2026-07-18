package com.example.dvely.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for V27 (design §3) that ordinary JPA/service tests cannot catch (mirrors
 * ProjectInfrastructureSettingsSchemaTest/ProjectVersionLockSchemaTest): Hibernate's
 * {@code ddl-auto: validate} only checks column existence/type, never precision/scale/nullability
 * or FK delete behavior — a migration with a wrong DECIMAL scale or a missing ON DELETE CASCADE
 * would still let the app boot.
 */
@SpringBootTest
class ProjectBudgetSettingsSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v27MigrationAppliedSuccessfully() {
        String v27Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '27'
                        """,
                String.class
        );

        assertTrue("1".equals(v27Applied) || "true".equalsIgnoreCase(v27Applied));
    }

    @Test
    void projectBudgetSettingsTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name = 'project_budget_settings'
                        """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }

    @Test
    void monthlyBudgetAmountIsDecimal12Scale2AndNotNull() {
        var row = jdbcTemplate.queryForMap(
                """
                        select is_nullable, numeric_precision, numeric_scale
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'project_budget_settings'
                          and column_name = 'monthly_budget_amount'
                        """
        );

        assertEquals("NO", row.get("is_nullable"));
        assertEquals(12L, ((Number) row.get("numeric_precision")).longValue());
        assertEquals(2L, ((Number) row.get("numeric_scale")).longValue());
    }

    @Test
    void currencyDefaultsToUsd() {
        String columnDefault = jdbcTemplate.queryForObject(
                """
                        select column_default
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'project_budget_settings'
                          and column_name = 'currency'
                        """,
                String.class
        );

        assertEquals("USD", columnDefault);
    }

    @Test
    void projectForeignKeyDeletesAsCascade() {
        String deleteRule = jdbcTemplate.queryForObject(
                """
                        select delete_rule
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = 'fk_project_budget_settings_project'
                        """,
                String.class
        );

        assertEquals("CASCADE", deleteRule);
    }
}
