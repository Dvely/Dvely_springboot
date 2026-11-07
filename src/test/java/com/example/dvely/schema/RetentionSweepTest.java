package com.example.dvely.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.retention.AgentRunRetentionStore;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3-3 보존 정책(이슈 #338)의 실동작 가드.
 *
 * <p>이 경로들은 스케줄러에서만 돌기 때문에 검증을 미루면 운영에서 처음 터진다. 특히 두 가지를
 * 여기서 고정한다.</p>
 *
 * <ol>
 *   <li><b>안전성</b> — 웹훅 스윕이 아직 처리되지 않은 배달을 지우지 않는다. GitHub 웹훅은
 *       우리 쪽에서 재전송을 요청할 수 없어서, 잘못 지우면 그 이벤트는 영구히 사라진다.</li>
 *   <li><b>종료성</b> — 배치 반복이 실제로 0 으로 수렴한다. agent_runs 쪽은 행을 지우는 것이
 *       아니라 컬럼을 NULL 로 만드는 UPDATE 라, MySQL Connector/J 가 바뀐 행이 아니라 조건에
 *       맞은 행 수를 돌려준다는 점 때문에 조건을 잘못 쓰면 무한 반복이 된다.</li>
 * </ol>
 */
@SpringBootTest
class RetentionSweepTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.now().minusDays(7);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private AgentRunRetentionStore agentRunRetentionStore;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearFixtures() {
        jdbcTemplate.update("delete from webhook_deliveries where delivery_id like 'retention-test-%'");
        jdbcTemplate.update("delete from agent_run_events where task_id like 'retention-test-%'");
        jdbcTemplate.update("delete from agent_runs where task_id like 'retention-test-%'");
    }

    // ---------------------------------------------------------------------
    // webhook_deliveries
    // ---------------------------------------------------------------------

    @Test
    void 오래된_터미널_배달만_지우고_처리중인_배달은_남긴다() {
        List<String> terminal = List.of("COMPLETED", "IGNORED", "FAILED");
        List<String> inFlight = List.of("PENDING", "RETRY_WAIT", "PROCESSING");
        terminal.forEach(status -> insertDelivery(status, LocalDateTime.now().minusDays(30)));
        inFlight.forEach(status -> insertDelivery(status, LocalDateTime.now().minusDays(30)));

        int deleted = sweepWebhookDeliveries();

        assertThat(deleted).isEqualTo(terminal.size());
        assertThat(survivingStatuses()).containsExactlyInAnyOrderElementsOf(inFlight);
    }

    @Test
    void 보존_기간_안의_배달은_터미널이어도_남긴다() {
        insertDelivery("COMPLETED", LocalDateTime.now().minusDays(30));
        insertDelivery("COMPLETED", LocalDateTime.now().minusHours(1));

        int deleted = sweepWebhookDeliveries();

        assertThat(deleted).isEqualTo(1);
        assertThat(survivingStatuses()).containsExactly("COMPLETED");
    }

    @Test
    void 배치_반복이_0_으로_수렴한다() {
        // 이 스윕은 delivery_id 접두사를 가리지 않고 CUTOFF 이전의 terminal 행을 전부 지운다.
        // 반면 @BeforeEach 가 지우는 것은 'retention-test-%' 뿐이다 — 다른 웹훅 테스트가 남긴
        // 오래된 terminal 행이 있으면 total 이 7 을 넘어 이 단언이 실행 순서에 따라 깨진다
        // (실제로 깨졌다: expected 7 but was 15). 먼저 비워서 이 테스트가 넣은 것만 세게 한다.
        // 어차피 아래 루프가 그 행들을 지우므로, 비우는 시점만 앞당기는 것이지 파괴 범위는 같다.
        drainSweepableDeliveries();

        for (int i = 0; i < 7; i++) {
            insertDelivery("COMPLETED", LocalDateTime.now().minusDays(30));
        }

        // batchSize 를 2 로 줘서 여러 번 돌게 만든다 — 마지막 호출은 반드시 0 이어야 한다.
        int total = 0;
        int affected;
        int guard = 0;
        do {
            affected = webhookDeliveryRepository.deleteTerminalBatch(CUTOFF, 2);
            total += affected;
            guard++;
        } while (affected > 0 && guard < 50);

        assertThat(total).isEqualTo(7);
        assertThat(guard).isLessThan(50);
    }

    private void drainSweepableDeliveries() {
        int guard = 0;
        while (webhookDeliveryRepository.deleteTerminalBatch(CUTOFF, 100) > 0 && ++guard < 100) {
            // 남은 것이 없을 때까지
        }
    }

    // ---------------------------------------------------------------------
    // agent_runs / agent_run_events
    // ---------------------------------------------------------------------

    @Test
    void 끝난_실행의_큰_컬럼만_비우고_진행중인_실행은_건드리지_않는다() {
        long userId = insertUser();
        insertAgentRun("retention-test-done", userId, "DONE", LocalDateTime.now().minusDays(120));
        insertAgentRun("retention-test-running", userId, "RUNNING", LocalDateTime.now().minusDays(120));

        int blanked = agentRunRetentionStore.blankTerminalPayloadsBatch(
                LocalDateTime.now().minusDays(90), 500);

        assertThat(blanked).isEqualTo(1);
        assertThat(planJsonOf("retention-test-done")).isNull();
        assertThat(failureLogOf("retention-test-done")).isNull();
        assertThat(planJsonOf("retention-test-running")).isNotNull();
        // 행 자체는 남는다 — preview_sessions · project_changes 의 FK 때문에 지울 수 없다.
        assertThat(statusOf("retention-test-done")).isEqualTo("DONE");
    }

    @Test
    void 이미_비운_실행은_다시_매칭되지_않아_반복이_끝난다() {
        long userId = insertUser();
        insertAgentRun("retention-test-done", userId, "DONE", LocalDateTime.now().minusDays(120));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);

        int first = agentRunRetentionStore.blankTerminalPayloadsBatch(cutoff, 500);
        int second = agentRunRetentionStore.blankTerminalPayloadsBatch(cutoff, 500);

        assertThat(first).isEqualTo(1);
        // 0 이 아니면 스케줄러의 do/while 이 영원히 끝나지 않는다.
        assertThat(second).isZero();
    }

    @Test
    void 끝난_실행의_진행_이벤트를_지운다() {
        long userId = insertUser();
        insertAgentRun("retention-test-done", userId, "DONE", LocalDateTime.now().minusDays(120));
        insertAgentRun("retention-test-running", userId, "RUNNING", LocalDateTime.now().minusDays(120));
        insertAgentRunEvent("retention-test-done", LocalDateTime.now().minusDays(120));
        insertAgentRunEvent("retention-test-done", LocalDateTime.now().minusDays(120));
        insertAgentRunEvent("retention-test-running", LocalDateTime.now().minusDays(120));

        int deleted = agentRunRetentionStore.deleteTerminalEventsBatch(
                LocalDateTime.now().minusDays(90), 500);

        assertThat(deleted).isEqualTo(2);
        assertThat(eventCountOf("retention-test-done")).isZero();
        assertThat(eventCountOf("retention-test-running")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // #618 — 스윕이 실행 중 태스크를 막지 않고, 지울 행만 읽는다
    // ---------------------------------------------------------------------

    @Test
    void 이벤트는_자신도_보존_기간을_넘겨야_지운다() {
        // 인덱스 범위로만 읽게 하려고 이벤트의 created_at 도 조건에 넣었다. 경계에 걸린 이벤트는 다음 스윕이 지운다.
        long userId = insertUser();
        insertAgentRun("retention-test-done", userId, "DONE", LocalDateTime.now().minusDays(120));
        insertAgentRunEvent("retention-test-done", LocalDateTime.now().minusDays(120));
        insertAgentRunEvent("retention-test-done", LocalDateTime.now().minusDays(10));

        int deleted = agentRunRetentionStore.deleteTerminalEventsBatch(LocalDateTime.now().minusDays(90), 500);

        assertThat(deleted).isEqualTo(1);
        assertThat(eventCountOf("retention-test-done")).isEqualTo(1);
    }

    @Test
    void 이벤트_삭제가_created_at_인덱스를_후보로_쓴다() {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                 where table_schema = database() and table_name = 'agent_run_events'
                   and index_name = 'idx_agent_run_events_created' and column_name = 'created_at'
                """, Integer.class)).isEqualTo(1);

        // 실제로 고르는 계획(range)은 행 수에 따라 달라 테스트 데이터로는 고정할 수 없다 — PR 에 대량 데이터 실측을
        // 남겼다. 여기서는 그 계획이 가능하도록 조건이 인덱스를 쓸 수 있는 모양인지만 지킨다.
        List<java.util.Map<String, Object>> plan = jdbcTemplate.queryForList("""
                explain delete from agent_run_events
                 where created_at < ?
                   and task_id in (select task_id from agent_runs
                                    where status in ('DONE', 'FAILED', 'CANCELLED') and created_at < ?)
                 limit 500
                """, CUTOFF, CUTOFF);
        assertThat(plan).anySatisfy(row -> assertThat(String.valueOf(row.get("possible_keys")))
                .contains("idx_agent_run_events_created"));
    }

    @Test
    void 비우기_배치가_잠긴_행을_기다리지_않는다() throws Exception {
        // REPEATABLE READ 에서는 UPDATE 가 검사한 행마다 잠금을 기다리고 잡는다. 그래서 보존 기간 안의 터미널 run 처럼
        // 조건에 맞지 않는 행이라도 다른 트랜잭션이 쥐고 있으면 스윕이 멈추고, 반대로 스윕이 쥔 잠금이 실행 중 태스크의
        // 리스 갱신을 막았다(실측 3.04초 대기, 완료 UPDATE 는 Lock wait timeout). READ COMMITTED 의 UPDATE 는 맞지
        // 않는 행을 마지막 커밋 버전으로 판정하고 지나간다. 스윕을 실제 빈으로 불러 격리 수준 설정까지 함께 본다.
        long userId = insertUser();
        insertAgentRun("retention-test-old-done", userId, "DONE", LocalDateTime.now().minusDays(120));
        insertAgentRun("retention-test-recent-done", userId, "DONE", LocalDateTime.now().minusDays(1));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var statement = other.prepareStatement(
                    "update agent_runs set updated_at = now() where task_id = 'retention-test-recent-done'")) {
                statement.executeUpdate();
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> sweep = executor.submit(() ->
                        agentRunRetentionStore.blankTerminalPayloadsBatch(LocalDateTime.now().minusDays(90), 500));
                int blanked;
                try {
                    blanked = sweep.get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    other.rollback();   // 스윕을 풀어 준 뒤 실패로 보고한다
                    sweep.get(60, TimeUnit.SECONDS);
                    throw new AssertionError("보존 스윕이 다른 트랜잭션이 쥔 행을 기다렸다(REPEATABLE READ 로 돌고 있다)");
                }
                assertThat(blanked).isGreaterThanOrEqualTo(1);
                assertThat(planJsonOf("retention-test-old-done")).isNull();
            } finally {
                other.rollback();
                executor.shutdownNow();
            }
        }
        assertThat(planJsonOf("retention-test-recent-done")).isNotNull();
    }

    // ---------------------------------------------------------------------

    private int sweepWebhookDeliveries() {
        int total = 0;
        int affected;
        do {
            affected = webhookDeliveryRepository.deleteTerminalBatch(CUTOFF, 500);
            total += affected;
        } while (affected > 0);
        return total;
    }

    private void insertDelivery(String status, LocalDateTime receivedAt) {
        jdbcTemplate.update(
                """
                        insert into webhook_deliveries (delivery_id, event_type, payload, status, received_at)
                        values (?, 'push', ?, ?, ?)
                        """,
                "retention-test-" + status + "-" + System.nanoTime(), "{}".getBytes(), status, receivedAt);
    }

    private List<String> survivingStatuses() {
        return jdbcTemplate.queryForList(
                "select status from webhook_deliveries where delivery_id like 'retention-test-%'",
                String.class);
    }

    private long insertUser() {
        String githubUserId = "gh-retention-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into users (github_user_id, user_name) values (?, 'retention-test')", githubUserId);
        return jdbcTemplate.queryForObject(
                "select user_id from users where github_user_id = ?", Long.class, githubUserId);
    }

    private void insertAgentRun(String taskId, long userId, String status, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                        insert into agent_runs (task_id, user_id, status, plan_json, failure_log, created_at)
                        values (?, ?, ?, '{"steps":[]}', 'boom', ?)
                        """,
                taskId, userId, status, createdAt);
    }

    private void insertAgentRunEvent(String taskId, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "insert into agent_run_events (task_id, event_type, status, created_at) values (?, 'STEP', 'RUNNING', ?)",
                taskId, createdAt);
    }

    private String planJsonOf(String taskId) {
        return jdbcTemplate.queryForObject(
                "select plan_json from agent_runs where task_id = ?", String.class, taskId);
    }

    private String failureLogOf(String taskId) {
        return jdbcTemplate.queryForObject(
                "select failure_log from agent_runs where task_id = ?", String.class, taskId);
    }

    private String statusOf(String taskId) {
        return jdbcTemplate.queryForObject(
                "select status from agent_runs where task_id = ?", String.class, taskId);
    }

    private Integer eventCountOf(String taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from agent_run_events where task_id = ?", Integer.class, taskId);
    }
}
