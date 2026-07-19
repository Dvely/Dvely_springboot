package com.example.dvely.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.webhook.application.WebhookService;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #70 (QA v2 §3, Critical) — real-MySQL regression coverage for the webhook worker's
 * permanent-halt bug: {@code WebhookServiceTest}'s mocked repository could never exercise the
 * actual UPDATE against the real {@code webhook_deliveries} table, so it never caught that V21's
 * {@code next_attempt_at DATETIME NOT NULL} contradicted {@code claim()}'s {@code SET
 * next_attempt_at = null}. Before V29, every method below reproduces QA's exact
 * {@code SQLIntegrityConstraintViolationException}; after V29, they prove the full lifecycle
 * (claim -> retry-with-backoff -> reclaim, and lease-expiry recovery -> reclaim) round-trips
 * cleanly, and that concurrent claims from two worker instances never double-dispatch the same
 * delivery.
 * <p>
 * This class shares the real, always-on {@code WebhookDeliveryWorker} scheduled bean with the rest
 * of the suite (same convention as {@code OrchestrationConcurrencyIntegrationTest} tolerating
 * {@code AgentOrchestrator}'s own scheduler) rather than trying to suppress it — {@code
 * @TestPropertySource}-ing a longer poll interval here would only stop *this test class's own*
 * Spring context's worker instance, not the separately-cached default context's, so it cannot
 * actually guarantee isolation and was dropped as false safety. Instead, every {@code
 * claimPending} call below passes a poll limit far larger than {@code
 * WebhookDeliveryWorker#CLAIM_BATCH_SIZE} (10): {@code findRunnableIds} orders by {@code
 * receivedAt asc}, so a small limit combined with older leftover claimable rows from earlier runs
 * of this same dedicated schema can silently crowd a freshly seeded row out of the batch — the
 * large limit makes every assertion depend only on this test's own row, not on shared-schema
 * ordering.
 */
@SpringBootTest
class WebhookDeliveryClaimIntegrationTest {

    // Comfortably larger than production's CLAIM_BATCH_SIZE (10) so a freshly seeded row is never
    // crowded out of the oldest-first candidate scan by other leftover rows in this shared schema.
    private static final int POLL_LIMIT = 5_000;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private WebhookService webhookService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── the core regression: claim()'s UPDATE against the real column ──────────────────────────

    @Test
    void claimSetsNextAttemptAtToNullWithoutViolatingTheNotNullConstraintQaFoundInProduction() {
        String deliveryId = uniqueId("claim-basic");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        // claimPending() is the real (transactional) production entry point — it runs verbatim the
        // "set ... next_attempt_at = null" UPDATE QA's stacktrace captured failing against the
        // pre-V29 schema (calling SpringDataWebhookDeliveryRepository#claim directly here would
        // hit "No EntityManager with actual transaction" since its @Modifying(flushAutomatically)
        // requires the surrounding @Transactional the adapter provides).
        List<String> claimed = webhookDeliveryRepository.claimPending("worker-a", POLL_LIMIT);

        assertThat(claimed).contains(deliveryId);
        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.PROCESSING.name());
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("lease_owner")).isEqualTo("worker-a");
        assertThat(((Number) row.get("attempt")).intValue()).isEqualTo(1);
    }

    @Test
    void claimedDeliveryIsNotReselectedByASubsequentPollWhileStillProcessing() {
        String deliveryId = uniqueId("claim-no-reselect");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        List<String> firstPoll = webhookDeliveryRepository.claimPending("worker-a", POLL_LIMIT);
        assertThat(firstPoll).contains(deliveryId);

        // Same delivery, a second worker instance's poll immediately after: must not double-claim
        // a row that is already PROCESSING with a null next_attempt_at.
        List<String> secondPoll = webhookDeliveryRepository.claimPending("worker-b", POLL_LIMIT);
        assertThat(secondPoll).doesNotContain(deliveryId);
        assertThat(fetchRow(deliveryId).get("lease_owner")).isEqualTo("worker-a");
    }

    // ── multi-instance lease: exactly one of two racing workers wins the claim ─────────────────

    @Test
    void concurrentClaimsFromTwoWorkerInstancesNeverBothWinTheSameDelivery() throws Exception {
        String deliveryId = uniqueId("claim-race");
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1), null, null);

        CountDownLatch startBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<List<String>>> futures;
        try {
            futures = pool.invokeAll(List.of(
                    raceClaim(startBarrier, "worker-race-a"),
                    raceClaim(startBarrier, "worker-race-b")
            ));
        } finally {
            pool.shutdown();
        }

        long winners = 0;
        for (Future<List<String>> future : futures) {
            List<String> claimed = future.get(10, TimeUnit.SECONDS);
            if (claimed.contains(deliveryId)) {
                winners++;
            }
        }
        assertThat(winners)
                .as("exactly one worker instance must win a real-DB claim race on the same delivery")
                .isEqualTo(1);
        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.PROCESSING.name());
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("lease_owner")).isIn("worker-race-a", "worker-race-b");
    }

    // ── retry-with-backoff after a claim, then reclaim once the backoff elapses ────────────────

    @Test
    void aFailedDeliveryIsRescheduledWithBackoffThenSuccessfullyReclaimedOnceItsTimeArrives() {
        String deliveryId = uniqueId("claim-retry-reclaim");
        // "push" with no "ref" field deterministically throws inside the real
        // WebhookEventHandler.handlePush (requiredText guard) — exercising the genuine
        // claim -> processDelivery -> retry() path end to end, not a mocked handler.
        byte[] payloadMissingRef = "{\"repository\":{\"full_name\":\"octo/repo\"}}"
                .getBytes(StandardCharsets.UTF_8);
        seedDelivery(deliveryId, WebhookDeliveryStatus.PENDING, LocalDateTime.now().minusSeconds(1),
                null, null, "push", payloadMissingRef);

        List<String> claimed = webhookDeliveryRepository.claimPending("worker-retry", POLL_LIMIT);
        assertThat(claimed).contains(deliveryId);

        webhookService.processDelivery(deliveryId);

        Map<String, Object> afterFailure = fetchRow(deliveryId);
        assertThat(afterFailure.get("status")).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT.name());
        assertThat(afterFailure.get("lease_owner")).isNull();
        LocalDateTime nextAttemptAt = (LocalDateTime) afterFailure.get("next_attempt_at");
        assertThat(nextAttemptAt).isAfter(LocalDateTime.now()); // backoff pushed it into the future
        assertThat((String) afterFailure.get("error_message")).contains("ref");
        assertThat(((Number) afterFailure.get("attempt")).intValue()).isEqualTo(1);

        // Fast-forward the backoff instead of sleeping ~5s in the test — moves next_attempt_at
        // into the past exactly as real wall-clock time passing would.
        rewindToPast(deliveryId);

        List<String> reclaimed = webhookDeliveryRepository.claimPending("worker-retry-2", POLL_LIMIT);

        assertThat(reclaimed)
                .as("RETRY_WAIT deliveries whose backoff has elapsed must be reclaimable, same as PENDING ones")
                .contains(deliveryId);
        Map<String, Object> afterReclaim = fetchRow(deliveryId);
        assertThat(afterReclaim.get("status")).isEqualTo(WebhookDeliveryStatus.PROCESSING.name());
        assertThat(afterReclaim.get("next_attempt_at")).isNull();
        assertThat(afterReclaim.get("lease_owner")).isEqualTo("worker-retry-2");
        assertThat(((Number) afterReclaim.get("attempt")).intValue()).isEqualTo(2);
    }

    // ── insert path: a freshly enqueued delivery is immediately claimable ──────────────────────

    @Test
    void enqueueingANewDeliveryLeavesItImmediatelyClaimable() {
        String deliveryId = uniqueId("claim-enqueue");

        boolean accepted = webhookDeliveryRepository.enqueue(
                new WebhookDelivery(deliveryId, "push", "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(accepted).isTrue();
        Map<String, Object> row = fetchRow(deliveryId);
        assertThat(row.get("status")).isEqualTo(WebhookDeliveryStatus.PENDING.name());
        assertThat(row.get("next_attempt_at")).isNotNull();

        // webhook_deliveries.next_attempt_at is a plain DATETIME (whole-second precision), but
        // enqueue()'s LocalDateTime.now() carries nanoseconds — MySQL rounds that to the nearest
        // second on write, which can round *up* past the instant claimPending()'s own now() reads
        // a few milliseconds later. rewindToPast keeps this test's "is it actually claimable" proof
        // independent of that column-precision rounding, exactly like the backoff test below does.
        rewindToPast(deliveryId);
        assertThat(webhookDeliveryRepository.claimPending("worker-enqueue", POLL_LIMIT)).contains(deliveryId);
    }

    // ── lease-expiry recovery: a crashed worker's claimed (null next_attempt_at) row recovers ──

    @Test
    void anExpiredLeaseIsRecoveredBackIntoTheRetryQueueAndCanThenBeReclaimed() {
        String deliveryId = uniqueId("claim-lease-recover");
        // Mirrors exactly the state claim() itself leaves a row in mid-processing: PROCESSING,
        // next_attempt_at = null, but with a lease_until already in the past (as if the worker
        // that claimed it crashed before completing).
        seedDelivery(deliveryId, WebhookDeliveryStatus.PROCESSING, null,
                "worker-dead", LocalDateTime.now().minusMinutes(5));

        webhookDeliveryRepository.recoverExpiredLeases();

        Map<String, Object> afterRecovery = fetchRow(deliveryId);
        assertThat(afterRecovery.get("status")).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT.name());
        assertThat(afterRecovery.get("next_attempt_at")).isNotNull();
        assertThat(afterRecovery.get("lease_owner")).isNull();
        assertThat(afterRecovery.get("lease_until")).isNull();

        // The recovered row must itself be reclaimable once its "now" has genuinely passed —
        // proving recovery hands the delivery back to the queue rather than stranding it. Rewound
        // for the same whole-second DATETIME-vs-nanosecond-now() rounding reason as the enqueue
        // test above: recoverExpiredLeases() and this test's own claimPending() each capture an
        // independent LocalDateTime.now(), and MySQL's round-to-nearest-second storage of the
        // first can land a hair after the second's un-rounded comparison value.
        rewindToPast(deliveryId);
        assertThat(webhookDeliveryRepository.claimPending("worker-after-recovery", POLL_LIMIT)).contains(deliveryId);
        assertThat(fetchRow(deliveryId).get("next_attempt_at")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private Callable<List<String>> raceClaim(CountDownLatch startBarrier, String workerId) {
        return () -> {
            startBarrier.countDown();
            startBarrier.await();
            return webhookDeliveryRepository.claimPending(workerId, POLL_LIMIT);
        };
    }

    private String uniqueId(String label) {
        // delivery_id is VARCHAR(64) — a GitHub delivery UUID is 36 chars, so keep well under that.
        String raw = "it-70-" + label + "-" + System.nanoTime();
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }

    private void seedDelivery(String deliveryId, WebhookDeliveryStatus status, LocalDateTime nextAttemptAt,
                               String leaseOwner, LocalDateTime leaseUntil) {
        seedDelivery(deliveryId, status, nextAttemptAt, leaseOwner, leaseUntil, "push",
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    private void seedDelivery(String deliveryId, WebhookDeliveryStatus status, LocalDateTime nextAttemptAt,
                               String leaseOwner, LocalDateTime leaseUntil, String eventType, byte[] payload) {
        jdbcTemplate.update(
                """
                        insert into webhook_deliveries
                            (delivery_id, event_type, payload, status, attempt, max_attempts,
                             next_attempt_at, lease_owner, lease_until)
                        values (?, ?, ?, ?, 0, 5, ?, ?, ?)
                        """,
                deliveryId, eventType, payload, status.name(),
                nextAttemptAt == null ? null : Timestamp.valueOf(nextAttemptAt),
                leaseOwner,
                leaseUntil == null ? null : Timestamp.valueOf(leaseUntil)
        );
    }

    /**
     * Sets {@code next_attempt_at} a full second into the past, bypassing any dependency on
     * comparing two separately captured {@code LocalDateTime.now()} instants against a
     * whole-second-precision DATETIME column (see call sites for why that comparison alone is not
     * safely race-free at sub-second scale). Reused both to fast-forward a real retry backoff and
     * to make an "is this now claimable" check deterministic right after a write that itself used
     * {@code now()}.
     */
    private void rewindToPast(String deliveryId) {
        jdbcTemplate.update(
                "update webhook_deliveries set next_attempt_at = ? where delivery_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), deliveryId);
    }

    private Map<String, Object> fetchRow(String deliveryId) {
        return jdbcTemplate.queryForMap(
                "select * from webhook_deliveries where delivery_id = ?", deliveryId);
    }
}
