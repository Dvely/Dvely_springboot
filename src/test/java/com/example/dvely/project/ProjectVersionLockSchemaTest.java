package com.example.dvely.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for V26 (I45, #45) that ordinary JPA/service tests cannot catch (mirrors
 * ProjectInfrastructureSettingsSchemaTest/EnvironmentVariableSchemaTest): Hibernate's
 * {@code ddl-auto: validate} confirms the {@code version} column exists with a compatible type,
 * but never checks {@code NOT NULL}/{@code DEFAULT} — a migration that added a nullable version
 * column would still let the app boot, only to NPE the first time
 * {@link com.example.dvely.project.infrastructure.persistence.repository.ProjectRepositoryAdapter}
 * compares against a null stored version for an existing row.
 */
@SpringBootTest
class ProjectVersionLockSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v26MigrationAppliedSuccessfully() {
        String v26Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '26'
                        """,
                String.class
        );

        assertTrue("1".equals(v26Applied) || "true".equalsIgnoreCase(v26Applied));
    }

    @Test
    void versionColumnIsNotNullWithZeroDefault() {
        var row = jdbcTemplate.queryForMap(
                """
                        select is_nullable, column_default
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'projects'
                          and column_name = 'version'
                        """
        );

        assertEquals("NO", row.get("is_nullable"));
        assertEquals("0", String.valueOf(row.get("column_default")));
    }

    @Test
    void versionColumnIsBigint() {
        String dataType = jdbcTemplate.queryForObject(
                """
                        select data_type
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'projects'
                          and column_name = 'version'
                        """,
                String.class
        );

        assertEquals("bigint", dataType);
    }
}
