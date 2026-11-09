package com.example.dvely.support;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 테스트가 행을 SQL 로 직접 심을 때 쓴다.
 */
public final class JdbcRows {

    private JdbcRows() {
    }

    /**
     * INSERT 한 문장을 실행하고 <b>그 문장이</b> 만든 자동 증가 키를 돌려준다. 인자는 {@code jdbc.update(sql, args)} 와
     * 같은 방식으로 묶는다.
     *
     * <p>{@code jdbc.update(...)} 뒤에 {@code select last_insert_id()} 를 따로 부르면 안 된다. {@link JdbcTemplate} 은
     * 호출마다 풀에서 커넥션을 다시 빌리고, {@code last_insert_id()} 는 커넥션마다 따로 있는 값이다. 두 호출 사이에 같은
     * 컨텍스트의 스케줄 워커가 그 커넥션을 가져가면, 다른 커넥션의 값(0, 또는 그 커넥션이 전에 넣은 다른 행의 id)을
     * 받는다. CI 에서 {@code AuditLogRetentionSchedulerTest} 가 이렇게 간헐 실패했다(2026-11-09). 실측: INSERT 를 보낸
     * 커넥션을 다른 스레드가 쥐고 있으면 키 6175 대신 0 을 받았다.</p>
     */
    public static long insert(JdbcTemplate jdbc, String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            new ArgumentPreparedStatementSetter(args).setValues(statement);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "INSERT 가 자동 증가 키를 돌려주지 않았다: " + sql).longValue();
    }
}
