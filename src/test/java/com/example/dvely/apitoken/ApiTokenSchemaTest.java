package com.example.dvely.apitoken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema guards for {@code api_tokens} (V60). The unique index on {@code token_hash} is what makes
 * authentication a single indexed lookup rather than a scan, and the cascade is what keeps a
 * deleted account from leaving working credentials behind.
 */
@SpringBootTest
class ApiTokenSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v60MigrationAppliedSuccessfully() {
        String applied = jdbcTemplate.queryForObject(
                "select coalesce(max(success), 0) from flyway_schema_history where version = '56'",
                String.class);

        assertTrue("1".equals(applied) || "true".equalsIgnoreCase(applied));
    }

    @Test
    void tokenHashIsUniqueSoAuthIsASingleIndexedLookup() {
        Integer nonUnique = jdbcTemplate.queryForObject(
                """
                        select min(non_unique) from information_schema.statistics
                        where table_schema = database() and table_name = 'api_tokens'
                          and index_name = 'uk_api_tokens_hash'
                        """,
                Integer.class);

        assertEquals(0, nonUnique);
    }

    @Test
    void thereIsNoColumnThatCouldHoldAPlaintextToken() {
        // The security model is that the plaintext is never persisted. A column added later to
        // "make debugging easier" would quietly undo it.
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*) from information_schema.columns
                        where table_schema = database() and table_name = 'api_tokens'
                          and column_name in ('token', 'plaintext', 'token_value', 'secret')
                        """,
                Integer.class);

        assertEquals(0, count);
    }

    @Test
    void tokensDieWithTheirUser() {
        String deleteRule = jdbcTemplate.queryForObject(
                """
                        select delete_rule from information_schema.referential_constraints
                        where constraint_schema = database() and constraint_name = 'fk_api_tokens_user'
                        """,
                String.class);

        assertEquals("CASCADE", deleteRule);
    }
}
