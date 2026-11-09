package com.example.dvely.auth.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 매일 03:00 토큰 정리가 도는 동안에도 다른 사용자가 토큰을 회전하고 로그인할 수 있다(#625).
 *
 * <p>폐기된 리프레시 토큰을 지우는 문장은 {@code revoked} 에 인덱스가 없어 표 전체를 훑는다. 기본 격리(REPEATABLE READ)
 * 에서는 훑은 행과 틈을 커밋까지 전부 잠가, 그 사이 회전(UPDATE)은 잠금 대기로 실패하고 로그인(INSERT)은 정리가 끝날 때까지
 * 섰다. 이 테스트는 실제 정리 메서드의 트랜잭션을 그 문장 <b>뒤에서</b> 붙잡아 둔다 — 정리의 마지막 단계(만료 PAT 삭제)가
 * 지울 행 하나를 테스트가 먼저 잠가, 정리가 리프레시 토큰의 잠금을 쥔 채 커밋하지 못하게 한다.</p>
 *
 * <p>목·스파이 빈을 쓰지 않는다. 쓰면 테스트 컨텍스트가 하나 더 생기고, 컨텍스트마다 커넥션 풀(20)을 열어 두므로 MySQL
 * 기본 연결 한도(151)에 닿는다 — 실제로 다른 테스트 클래스의 컨텍스트가 "Too many connections" 로 뜨지 못했다.</p>
 */
@SpringBootTest
class TokenCleanupLockingIntegrationTest {

    private static final String MARK = "tc625-";

    @Autowired private TokenCleanupScheduler scheduler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private long sweeper;
    private long other;
    private long expiredApiToken;

    @BeforeEach
    void setUp() {
        cleanUp();
        sweeper = insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + "a" + System.nanoTime(), MARK + "a");
        other = insert("insert into users (github_user_id, user_name) values (?, ?)", MARK + "b" + System.nanoTime(), MARK + "b");
        // 회전이 남긴 폐기 행 200개와 살아 있는 행 100개 — 폐기 행이 다수인 평소 모양
        for (int i = 0; i < 300; i++) {
            jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at, revoked) values (?, ?, now() + interval 20 day, ?)",
                    sweeper, MARK + "t" + i, i < 200);
        }
        jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at, revoked) values (?, ?, now() + interval 20 day, 0)",
                other, MARK + "other-live");
        // 정리의 마지막 단계가 지울 PAT — 만료 뒤 유예(30일)도 지났다
        String hash = (MARK + "pat" + System.nanoTime() + "0".repeat(64)).substring(0, 64);
        expiredApiToken = insert("insert into api_tokens (user_id, token_hash, token_prefix, scope, expires_at)"
                + " values (?, ?, 'qp_tc625', 'READ', now() - interval 60 day)", sweeper, hash);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from api_tokens where token_hash like ?", MARK + "%");
        jdbc.update("delete from refresh_tokens where token_hash like ?", MARK + "%");
        jdbc.update("delete from users where github_user_id like ?", MARK + "%");
    }

    @Test
    void anotherUsersRotationAndLoginGoThroughWhileTheSweepHoldsItsTransaction() throws Exception {
        try (Connection holder = dataSource.getConnection(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select api_token_id from api_tokens where api_token_id = ? for update")) {
                lock.setLong(1, expiredApiToken);
                lock.executeQuery().close();
            }
            Future<?> sweep = pool.submit(scheduler::cleanupExpiredTokens);
            try {
                awaitRefreshTokensDeletedButNotCommitted();

                long started = System.nanoTime();
                int rotated = withTwoSecondLockWait(
                        "update refresh_tokens set revoked = 1 where token_hash = ? and revoked = 0 and expires_at > now()",
                        MARK + "other-live");
                int loggedIn = withTwoSecondLockWait(
                        "insert into refresh_tokens (user_id, token_hash, expires_at, revoked) values (" + other
                                + ", ?, now() + interval 30 day, 0)",
                        MARK + "other-new");
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;

                assertThat(rotated).as("회전").isEqualTo(1);
                assertThat(loggedIn).as("로그인").isEqualTo(1);
                assertThat(elapsedMs).as("정리를 기다리지 않았다").isLessThan(1_500L);
            } finally {
                holder.rollback();
                sweep.get(30, TimeUnit.SECONDS);
            }
        }

        // 정리 자체도 제 일을 했다 — 정리가 본 폐기 행은 지웠고 살아 있는 행은 남겼다
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked = 1", sweeper)).isZero();
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked = 0", sweeper)).isEqualTo(100);
        assertThat(count("select count(*) from api_tokens where api_token_id = ?", expiredApiToken)).isZero();
    }

    /**
     * 정리가 폐기 행을 지웠지만 아직 커밋하지 않은 순간까지. 커밋 전 삭제는 READ UNCOMMITTED 로만 보인다. 이때 정리는 그
     * 문장의 잠금을 쥐고 있고, 테스트가 PAT 행 잠금을 놓기 전에는 커밋할 수 없다(마지막 단계에서 그 잠금을 기다린다).
     *
     * <p>{@code information_schema.innodb_trx} 로 대기를 보면 더 직접적이지만, 앱 계정에는 그것을 읽을 권한(PROCESS)이 없다.</p>
     */
    private void awaitRefreshTokensDeletedButNotCommitted() throws Exception {
        try (Connection dirty = dataSource.getConnection()) {
            dirty.setAutoCommit(true);
            dirty.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            try (PreparedStatement revokedLeft = dirty.prepareStatement(
                    "select count(*) from refresh_tokens where user_id = ? and revoked = 1")) {
                revokedLeft.setLong(1, sweeper);
                for (int i = 0; i < 100; i++) {
                    try (var rows = revokedLeft.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) == 0) {
                            return;
                        }
                    }
                    Thread.sleep(100);
                }
            } finally {
                dirty.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
        }
        throw new AssertionError("정리가 폐기 행을 지우지 않았다 — 붙잡을 순간을 만들지 못해 이 테스트는 아무것도 보지 못한다");
    }

    /** 정리와 다른 커넥션에서, 잠금을 2초까지만 기다리며 실행한다. 잠금에 막히면 그 사실이 실패 메시지로 남는다. */
    private int withTwoSecondLockWait(String sql, String tokenHash) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement session = connection.createStatement()) {
                session.execute("set session innodb_lock_wait_timeout = 2");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenHash);
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new AssertionError("정리 트랜잭션의 잠금에 막혔다: " + e.getMessage(), e);
            } finally {
                try (Statement session = connection.createStatement()) {
                    session.execute("set session innodb_lock_wait_timeout = default");
                }
            }
        }
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
