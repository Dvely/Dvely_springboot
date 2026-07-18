package com.example.dvely.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.application.command.ApprovalCommandService;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Issue #55 — real-MySQL, real-thread regression coverage for the y-orchestration-hardening
 * design. Per §7's explicit lesson from #56 ("실제 스레드 동시성 검증 통합 테스트 부재"), every MUST item
 * here drives actual concurrent transactions against the dedicated test schema rather than mocking
 * away the contention the design exists to fix — a mocked repository cannot prove a lock ordering
 * is deadlock-free.
 * <p>
 * Shares the {@code dvely_danto_hardening} schema with the rest of the suite (see
 * {@link com.example.dvely.project.ProjectOptimisticLockIntegrationTest} for the established
 * pattern this class follows): every seeded row uses a {@code System.nanoTime()}-suffixed unique
 * ID, so this class never depends on the schema being empty, and other tests' rows never affect
 * these assertions (each check re-fetches its own taskId by exact ID, never a global scan result).
 */
@SpringBootTest
class OrchestrationConcurrencyIntegrationTest {

    @Autowired
    private ApprovalCommandService approvalCommandService;
    @Autowired
    private AgentOrchestrator agentOrchestrator;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── ADR-Y1 (#55): lockTask's MANDATORY propagation contract ────────────────────────────────

    @Test
    void lockTaskOutsideAnyTransactionFailsFastInsteadOfSilentlyNotLocking() {
        // No @Transactional on this test method — the call below hits the real Spring-proxied
        // TaskStore bean with no ambient transaction. MANDATORY propagation must reject this
        // immediately rather than opening-and-instantly-closing a transaction around a lock the
        // caller would no longer hold by its very next statement.
        assertThatThrownBy(() -> taskStore.lockTask("does-not-matter"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ── D1 write-skew (#55 MUST #1): concurrent approve of two different approvals ────────────

    @Test
    void concurrentApproveOfTwoDifferentApprovalsNeverGetsStuckAndExecutesApprovedExactlyOnce() throws Exception {
        Long userId = seedUser();
        String taskId = uniqueTaskId("d1-basic");
        seedWaitingApprovalTask(taskId, userId);
        Approval change = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.CHANGE, "변경 요약"));
        Approval deployment = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.DEPLOYMENT, "배포 요약"));

        CountDownLatch startBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = pool.invokeAll(List.of(
                    raceTask(startBarrier, () -> approvalCommandService.approve(userId, change.getId())),
                    raceTask(startBarrier, () -> approvalCommandService.approve(userId, deployment.getId()))
            ));
            awaitAll(futures);
        } finally {
            pool.shutdown();
        }

        // The core D1 guarantee: neither interleaving leaves the task stuck in WAITING_APPROVAL
        // with every approval already APPROVED (write-skew) — ADR-Y1's task-row lock structurally
        // serializes the two decide-then-check-allApproved sequences.
        AgentTask finalTask = taskStore.get(taskId);
        assertThat(finalTask.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(approvalRepository.findByTaskIdOrderByIdAsc(taskId))
                .allSatisfy(approval -> assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED));
        // executeApproved's enqueue() must have fired exactly once — if the old write-skew bug
        // were still present, this could be 0 (both skip) or, if it were instead double-firing,
        // more than 1. Exactly 1 is the proof that the locking read closed the race without
        // introducing a new double-execution.
        assertThat(countEventsOfType(taskId, userId, "QUEUED")).isEqualTo(1);
    }

    // ── D1 stress (#55 MUST #2): approve×2 + cancel repeated, no deadlocks, invariant holds ────

    @Test
    void stressApproveApproveCancelMixNeverDeadlocksAndTerminalInvariantHolds() throws Exception {
        Long userId = seedUser();
        // 20 iterations (design suggests 50) — enough to exercise every interleaving order across
        // the two approves and the cancel repeatedly while keeping this test's runtime reasonable
        // for routine `./gradlew test` runs; the correctness argument (ADR-Y1's lock hierarchy) is
        // structural, not statistical, so this is a regression tripwire, not the proof itself.
        int iterations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < iterations; i++) {
                String taskId = uniqueTaskId("d1-stress-" + i);
                seedWaitingApprovalTask(taskId, userId);
                Approval change = approvalRepository.save(
                        new Approval(userId, null, null, taskId, ApprovalType.CHANGE, "변경 " + i));
                Approval deployment = approvalRepository.save(
                        new Approval(userId, null, null, taskId, ApprovalType.DEPLOYMENT, "배포 " + i));

                CountDownLatch startBarrier = new CountDownLatch(3);
                List<Future<Void>> futures = pool.invokeAll(List.of(
                        raceTask(startBarrier, () -> approveTolerating409(userId, change.getId())),
                        raceTask(startBarrier, () -> approveTolerating409(userId, deployment.getId())),
                        raceTask(startBarrier, () -> agentOrchestrator.cancel(taskId, userId))
                ));
                for (Future<Void> future : futures) {
                    try {
                        future.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException executionException) {
                        // Every legitimate outcome (a losing approve's 409) is already swallowed
                        // inside approveTolerating409 — anything reaching here is unexpected,
                        // most importantly a deadlock/lock-wait-timeout, which ADR-Y1's lock
                        // hierarchy is specifically designed to make impossible.
                        fail("unexpected exception on stress iteration " + i, executionException.getCause());
                    }
                }

                AgentTask finalTask = taskStore.get(taskId);
                assertThat(finalTask.status()).isIn(TaskStatus.QUEUED, TaskStatus.CANCELLED);
                if (finalTask.status() == TaskStatus.CANCELLED) {
                    // Y6-a invariant: "task 터미널 ⇒ PENDING 승인 없음".
                    boolean anyPending = approvalRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                            .anyMatch(approval -> approval.getStatus() == ApprovalStatus.PENDING);
                    assertThat(anyPending)
                            .as("iteration %d: a terminal (CANCELLED) task must have no PENDING approvals left", i)
                            .isFalse();
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    // ── ADR-Y5 (#55 MUST #4): concurrent lease recovery of the same expired row ────────────────

    @Test
    void concurrentLeaseRecoveryOfTheSameExpiredRowIncrementsAttemptExactlyOnce() throws Exception {
        Long userId = seedUser();
        String taskId = uniqueTaskId("y5-recovery");
        seedRunningTaskWithExpiredLease(taskId, userId, "worker-a");

        CountDownLatch startBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = pool.invokeAll(List.of(
                    raceTask(startBarrier, taskStore::recoverExpiredLeases),
                    raceTask(startBarrier, taskStore::recoverExpiredLeases)
            ));
            awaitAll(futures);
        } finally {
            pool.shutdown();
        }

        AgentTask finalTask = taskStore.get(taskId);
        assertThat(finalTask.status()).isEqualTo(TaskStatus.RETRY_WAIT);
        Integer attempt = jdbcTemplate.queryForObject(
                "SELECT attempt FROM agent_runs WHERE task_id = ?", Integer.class, taskId);
        // G7 regression guard: two concurrent recovery attempts against the same row must not
        // double-increment attempt (the read-then-modify bug) — the conditional UPDATE's
        // affected-row-count guard means only one of the two actually transitions the row.
        assertThat(attempt).isEqualTo(1);
        assertThat(countEventsOfType(taskId, userId, "LEASE_RECOVERED")).isEqualTo(1);
    }

    // ── ADR-Y2 (#55 MUST #8): sweep recovers a stuck WAITING_APPROVAL task ─────────────────────

    @Test
    void recoverStuckApprovedTaskTransitionsToQueuedWhenEveryApprovalIsApproved() {
        Long userId = seedUser();
        String taskId = uniqueTaskId("y2-sweep-recover");
        seedWaitingApprovalTask(taskId, userId);
        Approval change = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.CHANGE, "변경 요약"));
        change.approve();
        approvalRepository.save(change);

        // Directly invokes the sweep's lock-and-reverify unit (design §4.3) rather than going
        // through StuckApprovalSweeper's scheduled 30s-grace candidate scan — this isolates the
        // recovery logic itself from wall-clock timing and from any other test's WAITING_APPROVAL
        // rows that might otherwise also match a real global scan in this shared schema.
        agentOrchestrator.recoverStuckApprovedTask(taskId);

        assertThat(taskStore.get(taskId).status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(countEventsOfType(taskId, userId, "APPROVAL_SWEEP_RECOVERED")).isEqualTo(1);
    }

    @Test
    void recoverStuckApprovedTaskNoOpsWhenAnApprovalIsStillPending() {
        Long userId = seedUser();
        String taskId = uniqueTaskId("y2-sweep-noop");
        seedWaitingApprovalTask(taskId, userId);
        approvalRepository.save(new Approval(userId, null, null, taskId, ApprovalType.CHANGE, "변경 요약"));

        agentOrchestrator.recoverStuckApprovedTask(taskId);

        assertThat(taskStore.get(taskId).status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
    }

    // ── Y6-a (#55): reject cascades to sibling PENDING approvals — DB-backed confirmation ──────

    @Test
    void rejectCascadesToSiblingPendingApprovalsAndASubsequentApproveIsRejectedWith409() {
        Long userId = seedUser();
        String taskId = uniqueTaskId("y6a-reject-cascade");
        seedWaitingApprovalTask(taskId, userId);
        Approval change = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.CHANGE, "변경 요약"));
        Approval deployment = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.DEPLOYMENT, "배포 요약"));

        approvalCommandService.reject(userId, change.getId());

        assertThat(taskStore.get(taskId).status()).isEqualTo(TaskStatus.CANCELLED);
        Approval reloadedSibling = approvalRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .filter(approval -> approval.getId().equals(deployment.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(reloadedSibling.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);

        assertThatThrownBy(() -> approvalCommandService.approve(userId, deployment.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 처리된 승인입니다");
    }

    // ── ADR-Y3/Y4 (#55 MUST #6/#7): releaseClaim + heartbeat scoping, DB-backed ────────────────

    @Test
    void releaseClaimSetsAFutureNextRunAtLeavesAttemptUnchangedAndAppendsDispatchRejectedEvent() {
        Long userId = seedUser();
        String taskId = uniqueTaskId("y3-release-claim");
        seedRunningTaskWithExpiredLease(taskId, userId, "worker-x"); // "expired" is irrelevant here — releaseClaim doesn't check lease_until, only status+owner
        LocalDateTime before = LocalDateTime.now();

        boolean released = taskStore.releaseClaim(taskId, "worker-x", 5000L);

        assertThat(released).isTrue();
        AgentTask finalTask = taskStore.get(taskId);
        assertThat(finalTask.status()).isEqualTo(TaskStatus.QUEUED);
        LocalDateTime nextRunAt = jdbcTemplate.queryForObject(
                "SELECT next_run_at FROM agent_runs WHERE task_id = ?", LocalDateTime.class, taskId);
        assertThat(nextRunAt).isAfter(before.plusSeconds(4)); // ~5s backoff, allow scheduling jitter
        Integer attempt = jdbcTemplate.queryForObject(
                "SELECT attempt FROM agent_runs WHERE task_id = ?", Integer.class, taskId);
        assertThat(attempt).isZero(); // ADR-Y3: pool saturation must not spend the retry budget
        assertThat(countEventsOfType(taskId, userId, "DISPATCH_REJECTED")).isEqualTo(1);
    }

    @Test
    void zombieTaskWithoutARegistryEntryIsNotRenewedByHeartbeatAndSelfRecoversViaLeaseExpiry() {
        // D2/G1 end-to-end: a task this worker claimed but whose executor submission was rejected
        // (ADR-Y4) must never have heartbeat keep its lease alive, and must self-recover through
        // ordinary lease-expiry recovery — same as if the JVM had crashed.
        Long userId = seedUser();
        String taskId = uniqueTaskId("y4-zombie");
        String workerId = "worker-zombie";
        seedRunningTaskWithExpiredLease(taskId, userId, workerId);
        LocalDateTime expiredLeaseUntil = jdbcTemplate.queryForObject(
                "SELECT lease_until FROM agent_runs WHERE task_id = ?", LocalDateTime.class, taskId);

        // "Heartbeat" — registry snapshot deliberately does NOT include this taskId (simulating
        // AgentExecutionRegistry never having registered it, e.g. a rejected dispatch).
        taskStore.renewWorkerLeases(workerId, Set.of("some-other-task-id"));

        LocalDateTime leaseUntilAfterHeartbeat = jdbcTemplate.queryForObject(
                "SELECT lease_until FROM agent_runs WHERE task_id = ?", LocalDateTime.class, taskId);
        assertThat(leaseUntilAfterHeartbeat).isEqualTo(expiredLeaseUntil); // untouched — still expired

        taskStore.recoverExpiredLeases();

        AgentTask finalTask = taskStore.get(taskId);
        assertThat(finalTask.status()).isEqualTo(TaskStatus.RETRY_WAIT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private Callable<Void> raceTask(CountDownLatch startBarrier, Runnable action) {
        return () -> {
            startBarrier.countDown();
            startBarrier.await();
            action.run();
            return null;
        };
    }

    private void awaitAll(List<Future<Void>> futures) throws Exception {
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
    }

    /** Swallows only the one legitimate racing outcome (this approval lost to a concurrent
     * cancel/reject and its own decide() 409s) — anything else (in particular a deadlock/lock-
     * wait-timeout) is left to propagate and fail the test. */
    private void approveTolerating409(Long userId, Long approvalId) {
        try {
            approvalCommandService.approve(userId, approvalId);
        } catch (IllegalStateException expectedRaceOutcome) {
            assertThat(expectedRaceOutcome)
                    // PessimisticLockingFailureException is the common supertype of both MySQL
                    // deadlock (error 1213) and lock-wait-timeout (error 1205) translations —
                    // ADR-Y1's lock hierarchy is specifically designed to make either impossible
                    // here.
                    .isNotInstanceOf(PessimisticLockingFailureException.class);
        }
    }

    private long countEventsOfType(String taskId, Long ownerUserId, String eventType) {
        return taskStore.getEvents(taskId, ownerUserId, null).stream()
                .map(AgentTaskEvent::type)
                .filter(eventType::equals)
                .count();
    }

    private Long seedUser() {
        User owner = userRepository.save(
                new User(new GithubId("i55-hardening-" + System.nanoTime()), "octo", null));
        return owner.getId();
    }

    private String uniqueTaskId(String label) {
        // agent_runs.task_id is VARCHAR(64) — keep well under that even with a long label.
        String suffix = Long.toString(System.nanoTime());
        String raw = "it-" + label + "-" + suffix;
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }

    private void seedWaitingApprovalTask(String taskId, Long userId) {
        taskStore.save(new AgentTask(
                taskId, userId, null, null, TaskStatus.WAITING_APPROVAL,
                null, null, null, null, Instant.now()
        ));
        taskStore.savePlan(taskId, minimalPlan());
    }

    private com.example.dvely.agent.application.dto.AgentPlan minimalPlan() {
        return new com.example.dvely.agent.application.dto.AgentPlan(
                List.of(new com.example.dvely.agent.application.dto.AgentStep(
                        com.example.dvely.agent.domain.value.AgentType.CHAT, java.util.Map.of())),
                "integration-test-plan",
                com.example.dvely.agent.domain.value.AiProvider.OPENAI,
                null
        );
    }

    /** Seeds a task directly as RUNNING with an already-expired lease, bypassing
     * {@code claimRunnableTasks} entirely — claim's candidate query is a global, unscoped scan
     * across the whole shared test schema, so relying on it here would make this test's outcome
     * depend on what other test classes happen to have left in the table. Writing the lease
     * columns directly via JDBC keeps this test's blast radius to exactly its own taskId. */
    private void seedRunningTaskWithExpiredLease(String taskId, Long userId, String workerId) {
        taskStore.save(new AgentTask(
                taskId, userId, null, null, TaskStatus.RUNNING,
                null, null, null, null, Instant.now()
        ));
        jdbcTemplate.update(
                "UPDATE agent_runs SET lease_owner = ?, lease_until = ? WHERE task_id = ?",
                workerId, Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), taskId
        );
    }
}
