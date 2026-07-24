package com.example.dvely.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #74 (design ad-audit-log-design.md §11, following #70's lesson — see
 * {@code WebhookDeliverySchemaTest}): {@code ddl-auto: validate} never checks column nullability
 * or the absence of foreign keys, so only a real-schema assertion like this one proves V30 landed
 * exactly as designed — nullable columns actually nullable (ADR-A4's structural columns), NOT NULL
 * columns actually required, and zero foreign keys (ADR-A3 — this table must never be pulled into
 * another table's lock graph).
 */
@SpringBootTest
class AuditLogSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v30MigrationAppliedSuccessfully() {
        String v30Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '30'
                        """,
                String.class
        );

        assertThat("1".equals(v30Applied) || "true".equalsIgnoreCase(v30Applied)).isTrue();
    }

    @Test
    void auditLogsTableExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name = 'audit_logs'
                        """,
                Integer.class
        );

        assertThat(count).isEqualTo(1);
    }

    @Test
    void expectedIndexesExist() {
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                        select distinct index_name
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'audit_logs'
                          and index_name != 'PRIMARY'
                        """,
                String.class
        );

        assertThat(indexNames).containsExactlyInAnyOrder(
                "idx_audit_logs_project",
                "idx_audit_logs_project_category",
                "idx_audit_logs_created"
        );
    }

    @Test
    void requiredColumnsAreNotNullable() {
        Map<String, String> nullability = columnNullability();

        assertThat(nullability.get("category")).isEqualTo("NO");
        assertThat(nullability.get("action")).isEqualTo("NO");
        assertThat(nullability.get("outcome")).isEqualTo("NO");
        assertThat(nullability.get("actor_type")).isEqualTo("NO");
        assertThat(nullability.get("created_at")).isEqualTo("NO");
    }

    @Test
    void optionalColumnsAreNullable() {
        Map<String, String> nullability = columnNullability();

        assertThat(nullability.get("actor_user_id")).isEqualTo("YES");
        assertThat(nullability.get("project_id")).isEqualTo("YES");
        assertThat(nullability.get("resource_type")).isEqualTo("YES");
        assertThat(nullability.get("resource_id")).isEqualTo("YES");
        assertThat(nullability.get("task_id")).isEqualTo("YES");
        assertThat(nullability.get("approval_id")).isEqualTo("YES");
        assertThat(nullability.get("detail")).isEqualTo("YES");
        assertThat(nullability.get("error_summary")).isEqualTo("YES");
    }

    @Test
    void hasNoForeignKeys() {
        // ADR-A3 regression guard: a foreign key added here later (e.g. "just add project_id ->
        // projects for tidiness") would pull audit inserts into the projects hot-row S-lock path
        // (design F10) — this table must stay a lock-hierarchy leaf.
        Integer fkCount = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and table_name = 'audit_logs'
                        """,
                Integer.class
        );

        assertThat(fkCount).isEqualTo(0);
    }

    private Map<String, String> columnNullability() {
        return jdbcTemplate.query(
                """
                        select column_name, is_nullable
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'audit_logs'
                        """,
                (rs, rowNum) -> Map.entry(rs.getString("column_name"), rs.getString("is_nullable"))
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
