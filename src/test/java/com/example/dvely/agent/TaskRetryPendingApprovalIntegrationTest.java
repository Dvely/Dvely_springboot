package com.example.dvely.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #57 (QA full-api-conformance-report §5.6/H1, Track B §11 H1+M3) — real-DB regression
 * coverage for the {@code retryable}/{@code pendingApprovalId} read-side fields actually agreeing
 * with what {@code POST /agent/tasks/{taskId}/retry} enforces.
 * <p>
 * Before this fix, {@code GET /agent/tasks/{id}} could report {@code retryable:true} for a FAILED
 * task (attempt &lt; maxAttempts) that still had a PENDING approval outstanding — e.g. the
 * "자동 수정 및 재build" CHANGE approval {@code BuildFailureRecoveryService} creates — while the real
 * {@code /retry} endpoint (backed by {@link AgentOrchestrator#retry}) always 409'd in that exact
 * state. This class proves, against the real dedicated-schema MySQL (same convention as {@link
 * OrchestrationConcurrencyIntegrationTest}), that {@link AgentOrchestrator#findPendingApprovalId}
 * — the single judgment now shared by both the read side and the {@code retry()} action — reports
 * the outstanding approval while it is PENDING, and that {@code retry()} itself only starts
 * succeeding once that same approval is no longer PENDING.
 */
@SpringBootTest
class TaskRetryPendingApprovalIntegrationTest {

    @Autowired
    private AgentOrchestrator agentOrchestrator;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void retryStaysBlockedWhileTheRecoveryApprovalIsPendingAndSucceedsOnceItIsDecided() {
        Long userId = seedUser();
        String taskId = "it-57-retry-pending-" + System.nanoTime();
        // Seed a FAILED task with attempt(0) < maxAttempts(3) — by attempt-count alone this is
        // exactly the "retryable" shape the QA report's H1 found misleading.
        taskStore.save(new AgentTask(
                taskId, userId, null, null, TaskStatus.QUEUED,
                null, null, null, null, Instant.now()
        ));
        taskStore.markFailed(taskId, "빌드 실패", "로그 일부", "수정안");
        Approval recoveryApproval = approvalRepository.save(new Approval(
                userId, null, null, taskId, ApprovalType.CHANGE,
                "자동 수정 및 재build: 의존성 설치 후 재빌드"
        ));

        // While the recovery approval is PENDING: the read-side judgment must report it, and the
        // actual retry() gate must refuse — matching the real /retry 409 the QA report reproduced.
        assertThat(agentOrchestrator.findPendingApprovalId(taskId)).isEqualTo(recoveryApproval.getId());
        assertThat(agentOrchestrator.retry(taskId, userId)).isFalse();
        assertThat(taskStore.get(taskId).status())
                .as("a refused retry() must never have spent the task's attempt budget")
                .isEqualTo(TaskStatus.FAILED);

        // Decide the approval (mirrors POST /approvals/{id}/approve) — this is the "approval
        // itself triggers the real recovery" path the QA report's suggested fix directs users to.
        recoveryApproval.approve();
        approvalRepository.save(recoveryApproval);

        // Now that no approval is PENDING: the read-side judgment clears, and retry() actually
        // succeeds (no 409) — the exact "retryable:true now really means /retry works" guarantee
        // #57 was raised for.
        assertThat(agentOrchestrator.findPendingApprovalId(taskId)).isNull();
        assertThat(agentOrchestrator.retry(taskId, userId)).isTrue();
        assertThat(taskStore.get(taskId).status()).isEqualTo(TaskStatus.RETRY_WAIT);
    }

    private Long seedUser() {
        User owner = userRepository.save(
                new User(new GithubId("i57-retry-pending-" + System.nanoTime()), "octo", null));
        return owner.getId();
    }
}
