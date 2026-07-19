package com.example.dvely.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.facade.AgentFacade;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.result.AgentSubmitResult;
import com.example.dvely.agent.application.service.AgentEventStreamService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.presentation.dto.DecisionRequest;
import com.example.dvely.agent.presentation.dto.TaskInputRequest;
import com.example.dvely.agent.presentation.dto.TaskStatusResponse;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.service.PreviewSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentControllerTest {

    private TaskStore taskStore;
    private InputWaitStore inputWaitStore;
    private AgentFacade agentFacade;
    private AgentOrchestrator agentOrchestrator;
    private AgentController controller;
    private AgentTask task;

    @BeforeEach
    void setUp() {
        taskStore = mock(TaskStore.class);
        inputWaitStore = mock(InputWaitStore.class);
        agentFacade = mock(AgentFacade.class);
        agentOrchestrator = mock(AgentOrchestrator.class);
        controller = new AgentController(
                agentFacade,
                agentOrchestrator,
                taskStore,
                inputWaitStore,
                mock(PreviewSessionService.class),
                mock(AgentEventStreamService.class)
        );
        task = new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.WAITING_INPUT,
                null,
                null,
                null,
                "question",
                Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(task);
    }

    @Test
    void decideDelegatesPlanAndSubmissionFlowToFacade() {
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정"))),
                "reason",
                AiProvider.OPENAI,
                11L
        );
        AgentSubmission submission = new AgentSubmission(
                "task-2",
                TaskStatus.WAITING_APPROVAL,
                List.of(101L)
        );
        when(agentFacade.submit(1L, 11L, 21L, "수정해줘", AiProvider.OPENAI))
                .thenReturn(new AgentSubmitResult(plan, submission));

        var response = controller.decide(
                1L,
                new DecisionRequest("수정해줘", AiProvider.OPENAI, 11L, 21L)
        );

        assertThat(response.taskId()).isEqualTo("task-2");
        assertThat(response.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.approvalIds()).containsExactly(101L);
        verify(agentFacade).submit(1L, 11L, 21L, "수정해줘", AiProvider.OPENAI);
        verifyNoInteractions(agentOrchestrator);
    }

    @Test
    void foreignUserCannotReadTaskStatus() {
        assertThatThrownBy(() -> controller.getTaskStatus(2L, "task-1"))
                .isInstanceOf(NotFoundException.class);
    }

    // ── #57 (QA report §5.6/H1/M3): retryable/pendingApprovalId response assembly ──────────────

    @Test
    void getTaskStatusIsNotRetryableWhenAnApprovalIsStillPendingEvenWithAttemptsRemaining() {
        // The QA-reported drift (H1): attempt(0) < maxAttempts(3) alone used to make this
        // retryable:true, but a still-PENDING approval (e.g. BuildFailureRecoveryService's
        // "자동 수정 및 재build" CHANGE approval) makes the real POST /retry 409 every time.
        // retryable must fold in the exact same pending-approval check AgentOrchestrator.retry()
        // enforces, and pendingApprovalId must surface that approval so the task screen can link
        // straight to it (M3) instead of requiring a separate GET /approvals scan.
        AgentTask failedTask = new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.FAILED, null, null, "빌드 실패", null, Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(failedTask);
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("로그 일부", "수정안", 0, 3));
        when(agentOrchestrator.findPendingApprovalId("task-1")).thenReturn(77L);

        TaskStatusResponse response = controller.getTaskStatus(1L, "task-1").getBody();

        assertThat(response.retryable()).isFalse();
        assertThat(response.pendingApprovalId()).isEqualTo(77L);
    }

    @Test
    void getTaskStatusIsRetryableWhenNoApprovalIsPendingAndAttemptsRemain() {
        AgentTask failedTask = new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.FAILED, null, null, "빌드 실패", null, Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(failedTask);
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("로그 일부", "수정안", 0, 3));
        when(agentOrchestrator.findPendingApprovalId("task-1")).thenReturn(null);

        TaskStatusResponse response = controller.getTaskStatus(1L, "task-1").getBody();

        assertThat(response.retryable()).isTrue();
        assertThat(response.pendingApprovalId()).isNull();
    }

    @Test
    void getTaskStatusIsNeverRetryableOnceAttemptsAreExhaustedRegardlessOfApprovals() {
        // No PENDING approval this time — must still stay false once attempt reaches maxAttempts,
        // i.e. the new pendingApprovalId check is additive (AND), never a replacement for the
        // existing attempt<maxAttempts guard.
        AgentTask failedTask = new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.FAILED, null, null, "빌드 실패", null, Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(failedTask);
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("로그 일부", "수정안", 3, 3));
        when(agentOrchestrator.findPendingApprovalId("task-1")).thenReturn(null);

        TaskStatusResponse response = controller.getTaskStatus(1L, "task-1").getBody();

        assertThat(response.retryable()).isFalse();
    }

    @Test
    void getTaskStatusExposesPendingApprovalIdEvenWhileWaitingForTheInitialPlanApproval() {
        // pendingApprovalId is a general "what approval is this task blocked on" field (M3), not
        // limited to the FAILED/build-recovery case — a WAITING_APPROVAL task blocked on its
        // initial plan approval must link to it too, even though retryable correctly stays false
        // (only a FAILED task is ever retryable, regardless of pendingApprovalId).
        AgentTask waitingTask = new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.WAITING_APPROVAL, null, null, null, null, Instant.now()
        );
        when(taskStore.getOwned("task-1", 1L)).thenReturn(waitingTask);
        when(taskStore.getFailure("task-1", 1L)).thenReturn(null);
        when(agentOrchestrator.findPendingApprovalId("task-1")).thenReturn(9L);

        TaskStatusResponse response = controller.getTaskStatus(1L, "task-1").getBody();

        assertThat(response.retryable()).isFalse();
        assertThat(response.pendingApprovalId()).isEqualTo(9L);
    }

    @Test
    void foreignUserCannotSubmitTaskInput() {
        assertThatThrownBy(() -> controller.submitInput(2L, "task-1", new TaskInputRequest("secret")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignUserCannotCancelTask() {
        assertThatThrownBy(() -> controller.cancelTask(2L, "task-1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void ownerCanCancelTask() {
        when(agentOrchestrator.cancel("task-1", 1L)).thenReturn(true);

        assertThat(controller.cancelTask(1L, "task-1").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(agentOrchestrator).cancel("task-1", 1L);
    }

    @Test
    void ownerInputQueuesPersistentTask() {
        when(inputWaitStore.supply("task-1", 1L, "my-domain")).thenReturn(true);

        assertThat(controller.submitInput(1L, "task-1", new TaskInputRequest("my-domain")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(inputWaitStore).supply("task-1", 1L, "my-domain");
    }

    @Test
    void ownerCannotSubmitInputWhenTaskIsNotWaiting() {
        when(taskStore.getOwned("task-1", 1L)).thenReturn(new AgentTask(
                "task-1",
                1L,
                11L,
                21L,
                TaskStatus.RUNNING,
                null,
                null,
                null,
                null,
                Instant.now()
        ));

        assertThatThrownBy(() -> controller.submitInput(
                1L,
                "task-1",
                new TaskInputRequest("secret")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("입력을 기다리는");
    }
}
