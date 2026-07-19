package com.example.dvely.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for V28 (Track Z, #56) that ordinary JPA/service tests cannot catch
 * (mirrors ProjectBudgetSettingsSchemaTest/ProjectInfrastructureSettingsSchemaTest): Hibernate's
 * {@code ddl-auto: validate} only checks column existence/type, never default values or FK
 * delete behavior — a migration with the wrong default or a missing ON DELETE SET NULL would
 * still let the app boot.
 */
@SpringBootTest
class ResultApprovalSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v28MigrationAppliedSuccessfully() {
        String v28Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '28'
                        """,
                String.class
        );

        assertTrue("1".equals(v28Applied) || "true".equalsIgnoreCase(v28Applied));
    }

    @Test
    void resultApprovalRequiredDefaultsToOnMatchingTheOtherFourPolicies() {
        var row = jdbcTemplate.queryForMap(
                """
                        select is_nullable, column_default
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'project_approval_policies'
                          and column_name = 'result_approval_required'
                        """
        );

        assertEquals("NO", row.get("is_nullable"));
        // MySQL/MariaDB may surface a TINYINT(1) default as "1" — accept either textual form.
        assertTrue("1".equals(String.valueOf(row.get("column_default")))
                || "b'1'".equals(String.valueOf(row.get("column_default"))));
    }

    @Test
    void projectChangesGainsResultApprovalReflectionColumns() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'project_changes'
                          and column_name in ('approval_id', 'pr_number', 'merge_commit_sha', 'merged_at')
                        """,
                Integer.class
        );

        assertEquals(4, columnCount);
    }

    @Test
    void changeApprovalForeignKeySetsNullOnApprovalDelete() {
        String deleteRule = jdbcTemplate.queryForObject(
                """
                        select delete_rule
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = 'fk_project_changes_approval'
                        """,
                String.class
        );

        assertEquals("SET NULL", deleteRule);
    }
}
