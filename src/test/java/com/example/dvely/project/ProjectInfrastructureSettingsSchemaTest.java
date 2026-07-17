package com.example.dvely.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for V25 that ordinary JPA/service tests cannot catch (mirrors
 * CloudConnectionSchemaTest/EnvironmentVariableSchemaTest): Hibernate's {@code ddl-auto: validate}
 * checks column existence/type but never nullability, so a missing or reverted V25 migration
 * would still let the app boot fine against a stale schema — it would only fail at runtime the
 * first time a standalone approval tries to INSERT a NULL task_id (design §2/§7).
 */
@SpringBootTest
class ProjectInfrastructureSettingsSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v25MigrationAppliedSuccessfully() {
        String v25Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '25'
                        """,
                String.class
        );

        assertTrue("1".equals(v25Applied) || "true".equalsIgnoreCase(v25Applied));
    }

    @Test
    void infrastructureSettingsAndChangeTablesExist() {
        assertEquals(1, tableCount("project_infrastructure_settings"));
        assertEquals(1, tableCount("project_infrastructure_setting_changes"));
    }

    @Test
    void approvalsTaskIdIsNullableForStandaloneApprovals() {
        String nullable = jdbcTemplate.queryForObject(
                """
                        select is_nullable
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'approvals'
                          and column_name = 'task_id'
                        """,
                String.class
        );

        assertEquals("YES", nullable);
    }

    @Test
    void pendingChangeGuardIndexExists() {
        Long indexCount = jdbcTemplate.queryForObject(
                """
                        select count(distinct index_name)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'project_infrastructure_setting_changes'
                          and index_name = 'idx_infra_setting_changes_pending'
                        """,
                Long.class
        );

        assertEquals(1L, indexCount);
    }

    @Test
    void changeToApprovalForeignKeyDeletesAsSetNull() {
        String deleteRule = jdbcTemplate.queryForObject(
                """
                        select delete_rule
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = 'fk_infra_setting_changes_approval'
                        """,
                String.class
        );

        assertEquals("SET NULL", deleteRule);
    }

    private Integer tableCount(String tableName) {
        return jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name = ?
                        """,
                Integer.class,
                tableName
        );
    }
}
