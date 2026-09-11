package com.example.dvely.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.preview.application.service.PreviewSessionService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 7-1 (Issue #342) 의 실측 가드 — 프리뷰 페이지 <b>1 회 로드</b>가 {@code preview_sessions} 에
 * 보내는 UPDATE 횟수를 실제 MySQL 에서 센다.
 *
 * <p>왜 단위 테스트로 부족한가: 예전 경로의 비용은 "엔티티를 고쳐 {@code save} 했다"가 아니라
 * <b>그 결과로 나간 SQL</b> 이다. 모킹된 리포지토리는 그 SQL 을 보여주지 않는다. 그래서
 * {@code performance_schema} 의 문장 다이제스트 카운터를 전/후로 읽어 증가분을 센다.</p>
 *
 * <p>세는 범위를 {@code SCHEMA_NAME = DATABASE()} 로 좁힌다 — 이 MySQL 컨테이너는 병렬 워크트리가
 * 스키마만 달리해 공유하므로, 스키마를 안 좁히면 남의 테스트가 섞인다. 같은 스키마 안에서는
 * {@code cleanupExpired} 스케줄러가 남의 테스트가 남긴 만료 행을 정리하며 UPDATE 를 낼 수 있어,
 * 단정은 "요청 수보다 현저히 적다"로 둔다(고치기 전에는 요청 수와 같았다).</p>
 */
@SpringBootTest
class PreviewGatewayTouchWriteIntegrationTest {

    /** 문서 1 + 자산 19 — 평범한 Vite 빌드 한 페이지가 내는 요청 수와 같은 자릿수. */
    private static final int REQUESTS_PER_PAGE_LOAD = 20;

    @Autowired
    private PreviewSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onePageLoadDoesNotWriteTheSessionRowOncePerAsset() {
        Assumptions.assumeTrue(performanceSchemaAvailable(), "performance_schema 가 꺼져 있어 셀 수 없다");
        String sessionId = insertActiveSession(LocalDateTime.now());

        long before = updateCount();
        for (int i = 0; i < REQUESTS_PER_PAGE_LOAD; i++) {
            assertThat(sessionService.resolveGateway(sessionId, accessTokenOf(sessionId))).isPresent();
        }
        long delta = updateCount() - before;

        // 고치기 전: 요청마다 1 번 → 20. 고친 뒤: 스로틀 안이므로 0(다른 테스트가 남긴 만료 행을
        // 스케줄러가 정리하며 내는 UPDATE 여유로 2 까지 허용한다).
        assertThat(delta).isLessThanOrEqualTo(2);
    }

    /** 스로틀을 넘긴 첫 접근은 여전히 갱신한다 — 만료 연장이 죽어서는 안 된다. */
    @Test
    void anAccessAfterTheThrottleWindowStillWritesExactlyOnce() {
        Assumptions.assumeTrue(performanceSchemaAvailable(), "performance_schema 가 꺼져 있어 셀 수 없다");
        String sessionId = insertActiveSession(LocalDateTime.now().minusMinutes(5));

        long before = updateCount();
        for (int i = 0; i < REQUESTS_PER_PAGE_LOAD; i++) {
            sessionService.resolveGateway(sessionId, accessTokenOf(sessionId));
        }
        long delta = updateCount() - before;

        // 첫 요청이 갱신하고 나머지는 스로틀에 걸린다. 1 이 기대값이고, 스케줄러 여유로 3 까지 본다.
        assertThat(delta).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(3);
        LocalDateTime lastAccessed = jdbcTemplate.queryForObject(
                "select last_accessed_at from preview_sessions where preview_session_id = ?",
                LocalDateTime.class, sessionId);
        assertThat(lastAccessed).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    private boolean performanceSchemaAvailable() {
        try {
            jdbcTemplate.queryForObject(
                    "select count(*) from performance_schema.events_statements_summary_by_digest", Long.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 이 스키마에서 {@code preview_sessions} 를 고친 문장의 누적 실행 횟수. */
    private long updateCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                        select coalesce(sum(count_star), 0)
                          from performance_schema.events_statements_summary_by_digest
                         where schema_name = database()
                           and digest_text like 'UPDATE%'
                           and digest_text like '%preview_sessions%'
                        """,
                Long.class);
        return count == null ? 0L : count;
    }

    private String accessTokenOf(String sessionId) {
        return sessionId.replace("-", "");
    }

    /**
     * FK(user_id) 만 채우고 project/chat/task 는 NULL 로 둔다 — 이 테스트가 보는 것은 게이트웨이
     * 조회가 내는 쓰기뿐이고, 그 경로는 이 컬럼들을 읽지 않는다.
     */
    private String insertActiveSession(LocalDateTime lastAccessedAt) {
        String githubUserId = "u7-touch-" + UUID.randomUUID();
        jdbcTemplate.update("insert into users (github_user_id, user_name) values (?, ?)",
                githubUserId, "u7-touch");
        Long userId = jdbcTemplate.queryForObject(
                "select user_id from users where github_user_id = ?", Long.class, githubUserId);

        String sessionId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                        insert into preview_sessions
                            (preview_session_id, access_token, user_id, container_id, host_port,
                             status, public_url, expires_at, last_accessed_at)
                        values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                        """,
                sessionId, accessTokenOf(sessionId), userId, "container-" + sessionId, 32768,
                "https://qeploy.test/api/v1/previews/" + sessionId + "/" + accessTokenOf(sessionId) + "/",
                LocalDateTime.now().plusMinutes(30), lastAccessedAt);
        return sessionId;
    }
}
