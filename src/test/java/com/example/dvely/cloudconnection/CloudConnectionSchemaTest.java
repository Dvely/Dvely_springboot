package com.example.dvely.cloudconnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CloudConnectionSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void credentialColumnsUseLargeTextTypes() {
        String database = jdbcTemplate.queryForObject("select database()", String.class);
        Map<String, String> columnTypes = jdbcTemplate.query(
                """
                        select column_name, column_type
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'cloud_connections'
                          and column_name in ('secret_access_key', 'session_token', 'service_account_key_json')
                        """,
                (rs, rowNum) -> Map.entry(rs.getString("column_name"), rs.getString("column_type"))
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        String v10Applied = jdbcTemplate.queryForObject(
                """
                        select coalesce(max(success), 0)
                        from flyway_schema_history
                        where version = '10'
                        """,
                String.class
        );

        System.out.println("cloudConnectionSchema database=" + database
                + ", columns=" + columnTypes
                + ", v10Applied=" + v10Applied);

        assertEquals("mediumtext", columnTypes.get("secret_access_key"));
        assertEquals("mediumtext", columnTypes.get("session_token"));
        assertEquals("mediumtext", columnTypes.get("service_account_key_json"));
        assertTrue("1".equals(v10Applied) || "true".equalsIgnoreCase(v10Applied));
    }
}
