package com.example.dvely.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards that application tests cannot catch (mirrors CloudConnectionSchemaTest):
 * Hibernate's {@code ddl-auto: validate} checks column existence/type broadly, but not collation,
 * so a later, well-intentioned "normalize all VARCHAR columns to the table's default collation"
 * cleanup could silently flip {@code env_key} case-sensitivity (design D9) without any ordinary
 * JPA mapping test noticing it.
 */
@SpringBootTest
class EnvironmentVariableSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v22MigrationAppliedSuccessfully() {
        String v22Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '22'
                        """,
                String.class
        );

        assertTrue("1".equals(v22Applied) || "true".equalsIgnoreCase(v22Applied));
    }

    @Test
    void envValueColumnIsMediumtextForEncryptedContent() {
        String columnType = jdbcTemplate.queryForObject(
                """
                        select column_type
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'environment_variables'
                          and column_name = 'env_value'
                        """,
                String.class
        );

        assertEquals("mediumtext", columnType);
    }

    @Test
    void envKeyColumnUsesCaseSensitiveBinCollation() {
        String database = jdbcTemplate.queryForObject("select database()", String.class);
        String collation = jdbcTemplate.queryForObject(
                """
                        select collation_name
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'environment_variables'
                          and column_name = 'env_key'
                        """,
                String.class
        );

        System.out.println("environmentVariablesSchema database=" + database + ", env_key collation=" + collation);

        assertEquals("utf8mb4_bin", collation);
    }

    @Test
    void uniqueConstraintOnProjectScopeKeyExists() {
        Long indexCount = jdbcTemplate.queryForObject(
                """
                        select count(distinct index_name)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'environment_variables'
                          and index_name = 'uk_environment_variables_project_scope_key'
                        """,
                Long.class
        );

        assertEquals(1L, indexCount);
    }
}
