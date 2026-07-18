package com.example.dvely.agent.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEventEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunEventRepository;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskStoreTest {

    private final Map<String, AgentRunEntity> runs = new HashMap<>();
    private SpringDataAgentRunRepository runRepository;
    private SpringDataAgentRunEventRepository eventRepository;
    private TaskStore taskStore;

    @BeforeEach
    void setUp() {
        runRepository = mock(SpringDataAgentRunRepository.class);
        eventRepository = mock(SpringDataAgentRunEventRepository.class);
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            runs.put(run.getTaskId(), run);
            return run;
        });
        when(runRepository.findById(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(runs.get(invocation.getArgument(0))));
        when(runRepository.findByTaskIdAndOwnerUserId(any(String.class), any(Long.class)))
                .thenAnswer(invocation -> {
                    AgentRunEntity run = runs.get(invocation.getArgument(0));
                    Long ownerUserId = invocation.getArgument(1);
                    return run != null && run.getOwnerUserId().equals(ownerUserId)
                            ? Optional.of(run)
                            : Optional.empty();
                });
        // BLOCKING-3: models the PESSIMISTIC_WRITE-locked lookup backing
        // requireWaitingResultApproval — a plain in-memory stand-in is enough here since this
        // suite tests the state-machine guard itself, not real row-locking (that's covered by the
        // repository query being exercised against a real DB elsewhere).
        when(runRepository.findByTaskIdForUpdate(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(runs.get(invocation.getArgument(0))));
        taskStore = new TaskStore(runRepository, eventRepository, new ObjectMapper());
    }

    @Test
    void exposesTaskOnlyToOwner() {
        taskStore.save(task(TaskStatus.RUNNING));

        assertThat(taskStore.getOwned("task-1", 1L)).isNotNull();
        assertThat(taskStore.getOwned("task-1", 2L)).isNull();
    }

    @Test
    void foreignUserCannotCancelTask() {
        taskStore.save(task(TaskStatus.RUNNING));

        assertThat(taskStore.cancel("task-1", 2L)).isFalse();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void cancelledTaskCannotReturnToRunningOrDone() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.cancel("task-1", 1L)).isTrue();
        taskStore.markDone("task-1", "preview", "done");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void inputSurvivesWaitAndQueuesSameTask() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, "my-domain")).isTrue();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(taskStore.consumeInput("task-1")).contains("my-domain");
    }

    @Test
    void repeatedInputSupplyIsRejectedAfterTaskIsQueued() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, "first")).isTrue();
        assertThat(taskStore.supplyInput("task-1", 1L, "second")).isFalse();
        assertThat(taskStore.consumeInput("task-1")).contains("first");
    }

    @Test
    void blankInputSupplyIsRejectedAndTaskStaysWaiting() {
        taskStore.save(task(TaskStatus.WAITING_INPUT));

        assertThat(taskStore.supplyInput("task-1", 1L, " ")).isFalse();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.WAITING_INPUT);
        assertThat(taskStore.consumeInput("task-1")).isEmpty();
    }

    // ── Track Z (#56): result-approval gate state machine ───────────────────────────────────

    @Test
    void markWaitingResultApprovalTransitionsTask() {
        taskStore.save(task(TaskStatus.RUNNING));

        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.WAITING_RESULT_APPROVAL);
    }

    @Test
    void markWaitingResultApprovalIsANoOpWhenTaskIsAlreadyCancelled() {
        // BLOCKING-2 regression: a task cancelled mid-flight (DELETE /tasks/{id} arriving while
        // the gate's slow preview-branch push is still in progress) must never be revived into
        // WAITING_RESULT_APPROVAL by a gate call that started executing before the cancellation
        // landed.
        taskStore.save(task(TaskStatus.RUNNING));
        taskStore.cancel("task-1", 1L);

        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void resumeAfterResultApprovalRequeuesOnlyFromWaitingResultApproval() {
        taskStore.save(task(TaskStatus.RUNNING));
        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");

        assertThat(taskStore.resumeAfterResultApproval("task-1")).isTrue();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void resumeAfterResultApprovalGuardsAgainstNonWaitingState() {
        taskStore.save(task(TaskStatus.RUNNING));

        // Never entered WAITING_RESULT_APPROVAL (e.g. gate never fired, or a racing duplicate
        // approve arrives after the task already moved on) — must be rejected (E-RA-03), not
        // silently requeue a task from an unrelated state.
        assertThat(taskStore.resumeAfterResultApproval("task-1")).isFalse();
        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void resumeAfterResultApprovalIsNotReenterableOnceAlreadyResumed() {
        taskStore.save(task(TaskStatus.RUNNING));
        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");

        assertThat(taskStore.resumeAfterResultApproval("task-1")).isTrue();
        // A second, racing approve/resume call against the same task must not re-enqueue it a
        // second time now that it has already moved past WAITING_RESULT_APPROVAL.
        assertThat(taskStore.resumeAfterResultApproval("task-1")).isFalse();
    }

    // ── Track Z (#56) review follow-up (BLOCKING-3): requireWaitingResultApproval — the locked
    // precondition check that must run BEFORE ResultApprovalService#reflect()'s external merge. ──

    @Test
    void requireWaitingResultApprovalPassesSilentlyWhenTaskIsWaitingForResultApproval() {
        taskStore.save(task(TaskStatus.RUNNING));
        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");

        taskStore.requireWaitingResultApproval("task-1"); // must not throw
    }

    @Test
    void requireWaitingResultApprovalThrowsWhenTaskNeverEnteredTheGate() {
        taskStore.save(task(TaskStatus.RUNNING));

        assertThatThrownBy(() -> taskStore.requireWaitingResultApproval("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");
    }

    @Test
    void requireWaitingResultApprovalThrowsWhenTaskWasCancelledConcurrently() {
        // BLOCKING-3 regression: models the race where the task is cancelled between the gate
        // creating the RESULT approval and the user approving it — the locked precondition check
        // must reject the resume attempt (before any external merge is ever attempted), not
        // silently let it through.
        taskStore.save(task(TaskStatus.RUNNING));
        taskStore.markWaitingResultApproval("task-1", "[결과 반영] 요약");
        taskStore.cancel("task-1", 1L);

        assertThatThrownBy(() -> taskStore.requireWaitingResultApproval("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");
    }

    @Test
    void claimQueryNeverIncludesWaitingResultApprovalInItsStatusWhitelist() {
        // Structural guarantee (design F5/§3.1): a worker can only ever claim a task whose status
        // is in this exact whitelist. WAITING_RESULT_APPROVAL was added to TaskStatus without
        // being added here — captured and asserted directly (not merely inferred from an empty
        // result) so this fails loudly the moment someone "helpfully" widens the list.
        when(runRepository.findRunnableTaskIds(any(), any(), any())).thenReturn(java.util.List.of());

        taskStore.claimRunnableTasks("worker-1", 10);

        org.mockito.ArgumentCaptor<java.util.List<String>> statusesCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(runRepository).findRunnableTaskIds(statusesCaptor.capture(), any(), any());
        assertThat(statusesCaptor.getValue())
                .containsExactlyInAnyOrder(TaskStatus.QUEUED.name(), TaskStatus.RETRY_WAIT.name())
                .doesNotContain(TaskStatus.WAITING_RESULT_APPROVAL.name());
    }

    @Test
    void persistedPlanCanBeReadByNewStoreInstance() {
        taskStore.save(task(TaskStatus.PENDING));
        AgentPlan plan = new AgentPlan(
                java.util.List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정"))),
                "reason",
                AiProvider.OPENAI,
                11L
        );
        taskStore.savePlan("task-1", plan);

        TaskStore restartedStore = new TaskStore(runRepository, eventRepository, new ObjectMapper());

        assertThat(restartedStore.getPlan("task-1")).isEqualTo(plan);
    }

    // ── ADR-Y1 (#55): lockTask — the task-row mutex ──────────────────────────────────────────

    @Test
    void lockTaskReturnsTheTaskWhenFound() {
        taskStore.save(task(TaskStatus.WAITING_APPROVAL));

        AgentTask locked = taskStore.lockTask("task-1");

        assertThat(locked.taskId()).isEqualTo("task-1");
        assertThat(locked.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
    }

    @Test
    void lockTaskThrowsWhenTaskIsMissing() {
        assertThatThrownBy(() -> taskStore.lockTask("does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    // Note: lockTask's MANDATORY propagation contract (calling it outside any transaction must
    // fail with IllegalTransactionStateException) is a Spring-proxy-level guarantee that a plain
    // unit test — which invokes the real object directly, bypassing the transactional AOP proxy —
    // cannot exercise. See OrchestrationLockOrderIntegrationTest for the @SpringBootTest that
    // proves it against the real proxied bean.

    // ── ADR-Y3 (#55): releaseClaim ────────────────────────────────────────────────────────────

    @Test
    void releaseClaimAppendsDispatchRejectedEventOnlyWhenTheUpdateActuallyAffectedARow() {
        when(runRepository.releaseClaim(eq("task-1"), eq("worker-1"), any(), anyString(), anyString()))
                .thenReturn(1);

        boolean released = taskStore.releaseClaim("task-1", "worker-1", 5000L);

        assertThat(released).isTrue();
        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("DISPATCH_REJECTED");
        assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.QUEUED.name());
    }

    @Test
    void releaseClaimIsASilentNoOpWhenSomeoneElseAlreadyMovedTheRowOffRunning() {
        // Models losing the race to a concurrent recoverExpiredLeases attempt at the same row —
        // the conditional UPDATE's WHERE clause matches zero rows, so this must not append a
        // DISPATCH_REJECTED event describing a transition that never happened.
        when(runRepository.releaseClaim(eq("task-1"), eq("worker-1"), any(), anyString(), anyString()))
                .thenReturn(0);

        boolean released = taskStore.releaseClaim("task-1", "worker-1", 5000L);

        assertThat(released).isFalse();
        verify(eventRepository, never()).save(any());
    }

    // ── ADR-Y5 (#55): recoverExpiredLeases — atomized conditional UPDATEs ────────────────────

    @Test
    void recoverExpiredLeasesAppendsRecoveryExhaustedEventOnlyWhenTheExhaustedUpdateAffectsARow() {
        when(runRepository.findExpiredLeaseTaskIds(eq(TaskStatus.RUNNING.name()), any()))
                .thenReturn(List.of("task-1"));
        when(runRepository.failExhaustedLease(eq("task-1"), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        taskStore.recoverExpiredLeases();

        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("RECOVERY_EXHAUSTED");
        assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.FAILED.name());
        // The attempt-exhausted branch must win outright — recoverLease must not also be attempted
        // for the same taskId once failExhaustedLease already claimed the row.
        verify(runRepository, never()).recoverLease(anyString(), anyString(), any(), anyString());
    }

    @Test
    void recoverExpiredLeasesFallsBackToRecoverLeaseWhenAttemptBudgetRemains() {
        when(runRepository.findExpiredLeaseTaskIds(eq(TaskStatus.RUNNING.name()), any()))
                .thenReturn(List.of("task-1"));
        when(runRepository.failExhaustedLease(eq("task-1"), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        when(runRepository.recoverLease(eq("task-1"), anyString(), any(), anyString()))
                .thenReturn(1);

        taskStore.recoverExpiredLeases();

        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("LEASE_RECOVERED");
        assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.RETRY_WAIT.name());
    }

    @Test
    void recoverExpiredLeasesNoOpsWhenBothConditionalUpdatesMatchZeroRows() {
        // Models losing the race to a concurrent recovery attempt (G7 regression guard): some
        // other actor already moved this row off RUNNING between the candidate read and now.
        when(runRepository.findExpiredLeaseTaskIds(eq(TaskStatus.RUNNING.name()), any()))
                .thenReturn(List.of("task-1"));
        when(runRepository.failExhaustedLease(eq("task-1"), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        when(runRepository.recoverLease(eq("task-1"), anyString(), any(), anyString()))
                .thenReturn(0);

        taskStore.recoverExpiredLeases();

        verify(eventRepository, never()).save(any());
    }

    // ── ADR-Y4 (#55): renewWorkerLeases scoped to a registry snapshot ────────────────────────

    @Test
    void renewWorkerLeasesSkipsTheQueryWhenTheTaskIdSetIsEmpty() {
        taskStore.renewWorkerLeases("worker-1", Set.of());

        verify(runRepository, never()).renewWorkerLeases(anyString(), any(), anyString(), any());
    }

    @Test
    void renewWorkerLeasesQueriesWithExactlyTheGivenTaskIds() {
        taskStore.renewWorkerLeases("worker-1", Set.of("task-1", "task-2"));

        verify(runRepository, times(1))
                .renewWorkerLeases(eq("worker-1"), any(), eq(TaskStatus.RUNNING.name()), eq(Set.of("task-1", "task-2")));
    }

    // ── ADR-Y2 (#55): sweep support ───────────────────────────────────────────────────────────

    @Test
    void findStuckWaitingApprovalTaskIdsDelegatesWithWaitingApprovalStatusAndAGracePeriod() {
        when(runRepository.findStuckWaitingApprovalTaskIds(eq(TaskStatus.WAITING_APPROVAL.name()), any()))
                .thenReturn(List.of("task-1"));

        List<String> candidates = taskStore.findStuckWaitingApprovalTaskIds();

        assertThat(candidates).containsExactly("task-1");
        ArgumentCaptor<LocalDateTime> beforeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(runRepository).findStuckWaitingApprovalTaskIds(eq(TaskStatus.WAITING_APPROVAL.name()), beforeCaptor.capture());
        // Grace period must be strictly in the past (design ADR-Y2: 30s) — a bug that passed
        // "now" (or later) would sweep tasks whose approve() is still actively in flight.
        assertThat(beforeCaptor.getValue()).isBefore(LocalDateTime.now());
    }

    @Test
    void recoverStuckApprovalTransitionsToQueuedWithASweepSpecificEvent() {
        taskStore.save(task(TaskStatus.WAITING_APPROVAL));

        taskStore.recoverStuckApproval("task-1");

        assertThat(taskStore.getOwned("task-1", 1L).status()).isEqualTo(TaskStatus.QUEUED);
    }

    private AgentTask task(TaskStatus status) {
        return new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                status,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }
}
