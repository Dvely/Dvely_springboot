package com.example.dvely.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.application.command.ApprovalCommandService;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.application.service.ResultApprovalService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #55 §7 MUST #3 — "RESULT×cancel 경쟁 (#62 B3)". Uses a dedicated {@code @SpringBootTest}
 * (rather than folding into {@link OrchestrationConcurrencyIntegrationTest}) because {@link
 * ResultApprovalService} is {@code @MockitoBean}-replaced here to inject a controlled delay in
 * place of a real GitHub merge — {@code @MockitoBean} forces Spring to build a distinct
 * application context, so isolating it to its own class keeps that extra context load from being
 * paid by every other test in the suite.
 * <p>
 * Real {@link ApprovalCommandService}/{@link AgentOrchestrator}/{@link TaskStore} beans, real
 * MySQL row locking (same dedicated schema as the rest of the suite) — only the external GitHub
 * call inside {@code ResultApprovalService#reflect} is replaced, so the lock ordering under test
 * (ADR-Y1 §2.3 "후보 2") is exercised exactly as production code would run it.
 */
@SpringBootTest
class ResultApprovalCancelRaceIntegrationTest {

    @MockitoBean
    private ResultApprovalService resultApprovalService;

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

    @Test
    void resultApproveVsCancelNeverDeadlocksAndConvergesToCancelled() throws Exception {
        Long userId = seedUser();
        String taskId = "it-result-cancel-race-" + System.nanoTime();
        taskStore.save(new AgentTask(
                taskId, userId, null, null, TaskStatus.WAITING_RESULT_APPROVAL,
                null, "결과 요약", null, null, Instant.now()
        ));
        Approval resultApproval = approvalRepository.save(
                new Approval(userId, null, null, taskId, ApprovalType.RESULT, "[결과 반영] 요약"));
        // ADR-Y1 §2.4: RESULT approve holds the task lock across reflect()'s GitHub round trip
        // (p95 < 10s per #56 NFR) — 300ms here is enough to reliably force real overlap with the
        // concurrent cancel() below without making this test slow.
        when(resultApprovalService.reflect(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new ResultApprovalService.ReflectResult(null, "deadbeefcafebabe");
        });

        CountDownLatch startBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = pool.invokeAll(List.of(
                    raceTask(startBarrier, () -> {
                        try {
                            approvalCommandService.approve(userId, resultApproval.getId());
                        } catch (IllegalStateException expectedWhenCancelWonTheRace) {
                            // Task (or this RESULT approval, cascaded by Y6-a) moved to CANCELLED
                            // before this approve()'s task lock was acquired — 409, and critically
                            // reflect() is never called in this ordering (BLOCKING-3's guarantee).
                        }
                    }),
                    raceTask(startBarrier, () -> agentOrchestrator.cancel(taskId, userId))
            ));
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS); // surfaces any unexpected exception (deadlock etc.)
            }
        } finally {
            pool.shutdown();
        }

        // Deterministic regardless of which thread wins the task-lock race: QUEUED (RESULT
        // approve's outcome) is not a terminal status, so a cancel that loses the race still
        // succeeds afterward — either ordering converges to CANCELLED.
        assertThat(taskStore.get(taskId).status()).isEqualTo(TaskStatus.CANCELLED);
        // reflect() runs at most once — either the approve thread won and called it exactly once,
        // or cancel won first and the approve thread's precondition check rejected it before
        // reflect() was ever reached.
        verify(resultApprovalService, atMostOnce()).reflect(any());
    }

    private Callable<Void> raceTask(CountDownLatch startBarrier, Runnable action) {
        return () -> {
            startBarrier.countDown();
            startBarrier.await();
            action.run();
            return null;
        };
    }

    private Long seedUser() {
        User owner = userRepository.save(
                new User(new GithubId("i55-result-race-" + System.nanoTime()), "octo", null));
        return owner.getId();
    }
}
