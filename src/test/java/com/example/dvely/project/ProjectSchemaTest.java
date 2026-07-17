package com.example.dvely.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ProjectSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void projectSchemaDoesNotUseRepositoryTableAsProjectStorage() {
        assertEquals(1, tableCount("projects"));
        assertEquals(0, tableCount("repositories"));
        assertEquals(1, columnCount("projects", "project_id"));
        assertEquals(0, columnCount("projects", "repo_id"));
        assertEquals(1, columnCount("pipelines", "project_id"));
        assertEquals(1, columnCount("deployments", "project_id"));
        assertEquals(1, columnCount("domains", "project_id"));
        assertEquals(1, columnCount("chat_sessions", "project_id"));
        assertEquals(1, tableCount("project_approval_policies"));
        assertEquals(1, tableCount("approvals"));
        assertEquals(1, columnCount("approvals", "task_id"));
        assertEquals(1, tableCount("agent_runs"));
        assertEquals(1, tableCount("agent_run_events"));
        assertEquals(1, tableCount("preview_sessions"));
        assertEquals(1, columnCount("agent_runs", "plan_json"));
        assertEquals(1, columnCount("agent_runs", "lease_until"));
        assertEquals(1, columnCount("preview_sessions", "access_token"));
        assertEquals(1, tableCount("project_changes"));
        assertEquals(1, columnCount("project_changes", "diff_text"));
        assertEquals(1, tableCount("deployment_histories"));
        assertEquals(1, columnCount("deployment_histories", "correlation_id"));
        assertEquals(1, columnCount("deployment_histories", "commit_sha"));
        assertEquals(1, columnCount("deployment_histories", "lease_until"));
        assertEquals(1, tableCount("cloud_connection_verification_jobs"));
        assertEquals(1, columnCount("cloud_connection_verification_jobs", "connection_status"));
        assertEquals(1, columnCount("cloud_connection_verification_jobs", "lease_until"));
        assertEquals(1, tableCount("project_cloud_connection_settings"));
        assertEquals(1, columnCount("project_cloud_connection_settings", "cloud_connection_id"));
        assertEquals(1, columnCount("chat_sessions", "title"));
        assertEquals(1, columnCount("domains", "hosting_target"));
        assertEquals(1, columnCount("domains", "certificate_status"));
        assertEquals(1, columnCount("domains", "certificate_expires_at"));
        assertEquals(1, columnCount("projects", "repository_head_sha"));
        assertEquals(1, columnCount("projects", "repository_head_synced_at"));
        assertEquals(1, columnCount("projects", "repository_version"));
        assertEquals(1, tableCount("webhook_deliveries"));
        assertEquals(1, columnCount("webhook_deliveries", "delivery_id"));
        assertEquals(1, columnCount("webhook_deliveries", "next_attempt_at"));
        assertEquals(1, columnCount("webhook_deliveries", "lease_until"));
        assertEquals(1, columnCount("projects", "repository_connected_at"));
        assertEquals("YES", columnNullable("projects", "repository_connected_at"));

        String v13Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '13'
                        """,
                String.class
        );

        assertTrue("1".equals(v13Applied) || "true".equalsIgnoreCase(v13Applied));

        String v14Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '14'
                        """,
                String.class
        );

        assertTrue("1".equals(v14Applied) || "true".equalsIgnoreCase(v14Applied));

        String v15Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '15'
                        """,
                String.class
        );

        assertTrue("1".equals(v15Applied) || "true".equalsIgnoreCase(v15Applied));

        String v16Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '16'
                        """,
                String.class
        );

        assertTrue("1".equals(v16Applied) || "true".equalsIgnoreCase(v16Applied));

        String v17Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '17'
                        """,
                String.class
        );

        assertTrue("1".equals(v17Applied) || "true".equalsIgnoreCase(v17Applied));

        String v18Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '18'
                        """,
                String.class
        );

        assertTrue("1".equals(v18Applied) || "true".equalsIgnoreCase(v18Applied));

        String v19Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '19'
                        """,
                String.class
        );

        assertTrue("1".equals(v19Applied) || "true".equalsIgnoreCase(v19Applied));

        String v20Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '20'
                        """,
                String.class
        );

        assertTrue("1".equals(v20Applied) || "true".equalsIgnoreCase(v20Applied));

        String v21Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '21'
                        """,
                String.class
        );

        assertTrue("1".equals(v21Applied) || "true".equalsIgnoreCase(v21Applied));

        String v24Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '24'
                        """,
                String.class
        );

        assertTrue("1".equals(v24Applied) || "true".equalsIgnoreCase(v24Applied));
        assertEquals("CASCADE", foreignKeyDeleteRule("fk_chat_messages_session"));
        assertEquals("SET NULL", foreignKeyDeleteRule("fk_approvals_chat_session"));
        assertEquals("SET NULL", foreignKeyDeleteRule("fk_agent_runs_chat_session"));
        assertEquals("SET NULL", foreignKeyDeleteRule("fk_preview_sessions_chat_session"));
        assertEquals("SET NULL", foreignKeyDeleteRule("fk_project_changes_chat_session"));
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

    private Integer columnCount(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = ?
                          and column_name = ?
                        """,
                Integer.class,
                tableName,
                columnName
        );
    }

    private String columnNullable(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                        select is_nullable
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = ?
                          and column_name = ?
                        """,
                String.class,
                tableName,
                columnName
        );
    }

    private String foreignKeyDeleteRule(String constraintName) {
        return jdbcTemplate.queryForObject(
                """
                        select delete_rule
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = ?
                        """,
                String.class,
                constraintName
        );
    }
}
