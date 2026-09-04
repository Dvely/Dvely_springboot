package com.example.dvely.aiaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema-level guards for {@code ai_provider_credentials} (V44), mirroring
 * {@code EnvironmentVariableSchemaTest}. Hibernate's {@code ddl-auto: validate} checks column
 * existence and type, but not the two properties this table actually depends on for correctness:
 * the ciphertext column being wide enough, and the (user, provider) uniqueness that makes
 * "one key per vendor per user" a storage invariant rather than a service-layer hope.
 */
@SpringBootTest
class AiProviderCredentialSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v44MigrationAppliedSuccessfully() {
        String applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '44'
                        """,
                String.class
        );

        assertTrue("1".equals(applied) || "true".equalsIgnoreCase(applied));
    }

    @Test
    void encryptedApiKeyColumnIsMediumtext() {
        // AES-GCM ciphertext is Base64 of (12-byte IV + ciphertext + 16-byte tag), so it is
        // meaningfully longer than the plaintext key — a VARCHAR here would truncate silently.
        String columnType = jdbcTemplate.queryForObject(
                """
                        select column_type
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'ai_provider_credentials'
                          and column_name = 'encrypted_api_key'
                        """,
                String.class
        );

        assertEquals("mediumtext", columnType);
    }

    @Test
    void oneCredentialPerUserPerProviderIsEnforcedByAUniqueKey() {
        Integer nonUnique = jdbcTemplate.queryForObject(
                """
                        select min(non_unique)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'ai_provider_credentials'
                          and index_name = 'uk_ai_provider_credentials_user_provider'
                        """,
                Integer.class
        );

        assertEquals(0, nonUnique);
    }

    @Test
    void credentialsAreScopedToAUserAndCascadeOnUserDeletion() {
        // The user scope is the compliance boundary (no operator key pooling), and the cascade is
        // what keeps a deleted account from leaving its key behind in the database.
        String deleteRule = jdbcTemplate.queryForObject(
                """
                        select delete_rule
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = 'fk_ai_provider_credentials_user'
                        """,
                String.class
        );

        assertEquals("CASCADE", deleteRule);
    }
}
