package com.example.dvely.agent.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunEventRepository;
import com.example.dvely.agent.infrastructure.persistence.repository.SpringDataAgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
