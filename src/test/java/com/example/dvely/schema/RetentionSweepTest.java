package com.example.dvely.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.retention.AgentRunRetentionStore;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.time.LocalDateTime;
import java.util.List;
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
        insertAgentRunEvent("retention-test-done");
        insertAgentRunEvent("retention-test-done");
        insertAgentRunEvent("retention-test-running");

        int deleted = agentRunRetentionStore.deleteTerminalEventsBatch(
                LocalDateTime.now().minusDays(90), 500);

        assertThat(deleted).isEqualTo(2);
        assertThat(eventCountOf("retention-test-done")).isZero();
        assertThat(eventCountOf("retention-test-running")).isEqualTo(1);
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

    private void insertAgentRunEvent(String taskId) {
        jdbcTemplate.update(
                "insert into agent_run_events (task_id, event_type, status) values (?, 'STEP', 'RUNNING')",
                taskId);
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
