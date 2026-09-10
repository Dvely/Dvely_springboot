package com.example.dvely.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.common.worker.WorkQueue;
import com.example.dvely.common.worker.WorkQueuedEvent;
import com.example.dvely.common.worker.WorkerPollGate;
import com.example.dvely.webhook.domain.model.WebhookDelivery;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #340 5-1 — 폴링 구조를 바꾸고도 <b>깨지지 않아야 하는 두 가지</b>를 실제 MySQL 로 증명한다.
 *
 * <p>1. <b>claim 의 원자성.</b> 회수와 claim 을 한 트랜잭션으로 합쳤어도, 두 워커 인스턴스가
 * 같은 행을 동시에 노리면 정확히 하나만 집는다. 이 성질은 "후보를 SELECT 한 뒤 행 단위 조건부
 * UPDATE 로 집고, 영향받은 행 수로 성공을 판정한다"는 구조에서 나온다 — 합치기는 그 구조를
 * 건드리지 않으므로 성질도 그대로다. 말로 그렇다는 것과 실제로 그런 것은 다르므로 두 스레드로
 * 재현한다.</p>
 *
 * <p>2. <b>깨우기의 트랜잭션 경계.</b> 신호는 커밋 <b>뒤에</b> 도착해야 한다. 커밋 전에 깨우면
 * 워커가 아직 보이지 않는 행을 찾다가 빈손으로 돌아가 도로 백오프에 들어가고, 그 일은 백오프
 * 상한만큼 늦어진다.</p>
 *
 * <p>이 클래스가 웹훅 큐를 쓰는 이유는 {@code build.gradle} 이 테스트 JVM 전체에서 웹훅 워커의
 * 폴링을 1시간으로 꺼두기 때문이다 — 살아 있는 워커가 끼어들어 게이트 상태와 행을 흔들지 않는
 * 유일한 큐다.</p>
 */
@SpringBootTest
class WebhookMergedPollClaimIntegrationTest {

    /** 공유 스키마에 남은 오래된 행에 밀려 갓 심은 행이 배치에서 빠지지 않도록 넉넉히 잡는다. */
    private static final int POLL_LIMIT = 5_000;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private WorkerPollGate pollGate;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── 1. claim 원자성 ────────────────────────────────────────────────────────────────────────

    @Test
    void mergedPollRecoversAndClaimsInOneCall() {
        String deliveryId = uniqueId("merged-basic");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        List<String> claimed = webhookDeliveryRepository.recoverAndClaimPending("worker-a", POLL_LIMIT);

        assertThat(claimed).contains(deliveryId);
        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.PROCESSING.name());
        assertThat(row.get("lease_owner")).isEqualTo("worker-a");
    }

    @Test
    void concurrentMergedPollsFromTwoWorkerInstancesNeverBothWinTheSameDelivery() throws Exception {
        String deliveryId = uniqueId("merged-race");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        CountDownLatch startBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<List<String>>> futures;
        try {
            futures = pool.invokeAll(List.of(
                    racePoll(startBarrier, "worker-merged-a"),
                    racePoll(startBarrier, "worker-merged-b")
            ));
        } finally {
            pool.shutdown();
        }

        long winners = 0;
        for (Future<List<String>> future : futures) {
            if (future.get(20, TimeUnit.SECONDS).contains(deliveryId)) {
                winners++;
            }
        }
        assertThat(winners)
                .as("회수와 claim 을 한 트랜잭션으로 합쳐도 같은 행을 두 인스턴스가 집으면 안 된다")
                .isEqualTo(1);
        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.PROCESSING.name());
        assertThat(row.get("lease_owner")).isIn("worker-merged-a", "worker-merged-b");
    }

    @Test
    void alreadyClaimedRowIsNotReselectedByAnotherInstancesMergedPoll() {
        String deliveryId = uniqueId("merged-no-reselect");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        assertThat(webhookDeliveryRepository.recoverAndClaimPending("worker-a", POLL_LIMIT)).contains(deliveryId);
        assertThat(webhookDeliveryRepository.recoverAndClaimPending("worker-b", POLL_LIMIT))
                .doesNotContain(deliveryId);
        assertThat(fetchRow(deliveryId).get("lease_owner")).isEqualTo("worker-a");
    }

    /** 회수가 합친 뒤에도 실제로 돌아가는지 — 합치면서 조용히 빠뜨리면 좀비 리스가 영원히 남는다. */
    @Test
    void mergedPollStillRecoversAnExpiredLease() {
        String deliveryId = uniqueId("merged-recover");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PROCESSING, null,
                "worker-dead", LocalDateTime.now().minusMinutes(5));

        webhookDeliveryRepository.recoverAndClaimPending("worker-alive", POLL_LIMIT);

        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT.name());
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("lease_until")).isNull();
    }

    // ── 2. 깨우기의 트랜잭션 경계 ────────────────────────────────────────────────────────────────

    @Test
    void theWakeupSignalArrivesOnlyAfterTheEnqueueCommits() {
        backOffFully();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new WorkQueuedEvent(WorkQueue.WEBHOOK_DELIVERY));
            assertThat(pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY))
                    .as("커밋 전에 깨우면 워커가 아직 보이지 않는 행을 찾다가 도로 물러난다")
                    .isFalse();
        });

        assertThat(pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY))
                .as("커밋 직후에는 백오프가 얼마였든 다음 틱에 폴링해야 한다")
                .isTrue();
    }

    @Test
    void enqueueingARealDeliveryWakesTheWorkerQueue() {
        backOffFully();

        boolean accepted = webhookDeliveryRepository.enqueue(new WebhookDelivery(
                uniqueId("merged-wake"), "push", "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(accepted).isTrue();
        assertThat(pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY))
                .as("실제 enqueue 경로가 깨우기 신호를 내보내야 한다 — 아니면 배달이 백오프 상한만큼 방치된다")
                .isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * 게이트를 상한까지 물러나게 만든다. {@code recordIdle} 을 반복하면 간격이 두 배씩 늘어 상한에
     * 닿는다 — 벽시계를 기다리지 않고 "충분히 물러난 상태"를 만드는 방법이다. 앞의
     * {@code shouldPoll} 은 세대를 맞추기 위한 것으로, 이것이 없으면 이전 테스트가 남긴 깨우기
     * 때문에 {@code recordIdle} 이 조용히 무시된다.
     */
    private void backOffFully() {
        pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY);
        for (int i = 0; i < 6; i++) {
            pollGate.recordIdle(WorkQueue.WEBHOOK_DELIVERY);
        }
        assertThat(pollGate.shouldPoll(WorkQueue.WEBHOOK_DELIVERY)).isFalse();
    }

    private Callable<List<String>> racePoll(CountDownLatch startBarrier, String workerId) {
        return () -> {
            startBarrier.countDown();
            startBarrier.await();
            return webhookDeliveryRepository.recoverAndClaimPending(workerId, POLL_LIMIT);
        };
    }

    private String uniqueId(String label) {
        String raw = "it-340-" + label + "-" + System.nanoTime();
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }

    private void seedDelivery(String deliveryId, WebhookDeliveryStatus status, LocalDateTime nextAttemptAt,
                              String leaseOwner, LocalDateTime leaseUntil) {
        jdbcTemplate.update(
                """
                        insert into webhook_deliveries
                            (delivery_id, event_type, payload, status, attempt, max_attempts,
                             next_attempt_at, lease_owner, lease_until)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                deliveryId, "issues", "{}".getBytes(StandardCharsets.UTF_8), status.name(), 0, 5,
                nextAttemptAt == null ? null : Timestamp.valueOf(nextAttemptAt),
                leaseOwner,
                leaseUntil == null ? null : Timestamp.valueOf(leaseUntil)
        );
    }

    private Map<String, Object> fetchRow(String deliveryId) {
        return jdbcTemplate.queryForMap("select * from webhook_deliveries where delivery_id = ?", deliveryId);
    }
}
