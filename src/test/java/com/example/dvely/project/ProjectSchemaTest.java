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

        String v13Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '13'
                        """,
                String.class
        );

        assertTrue("1".equals(v13Applied) || "true".equalsIgnoreCase(v13Applied));
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
}
