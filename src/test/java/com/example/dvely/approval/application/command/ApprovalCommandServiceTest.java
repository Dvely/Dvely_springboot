package com.example.dvely.approval.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.repository.ApprovalRouting;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.change.application.service.ResultApprovalService;
import com.example.dvely.common.exception.NotFoundException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ApprovalCommandServiceTest {

    @Test
    void executesTaskOnlyAfterEveryApprovalIsApproved() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository,
                queryService,
                orchestrator,
                messageService,
                List.of(),
                mock(ResultApprovalService.class),
                taskStore
        );
        Approval change = approval(1L, ApprovalType.CHANGE, 21L);
        Approval deployment = approval(2L, ApprovalType.DEPLOYMENT, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        routingFor(repository, 2L, 7L, "task-1", ApprovalType.DEPLOYMENT);
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(change));
        when(repository.findByIdAndOwnerUserIdForUpdate(2L, 7L)).thenReturn(Optional.of(deployment));
        when(repository.save(change)).thenReturn(change);
        when(repository.save(deployment)).thenReturn(deployment);
        when(repository.findByTaskIdOrderByIdAscForUpdate("task-1"))
                .thenReturn(List.of(change, deployment));
        when(queryService.toResult(change)).thenReturn(result(change));
        when(queryService.toResult(deployment)).thenReturn(result(deployment));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.approve(7L, 1L);

        verify(orchestrator, never()).executeApproved("task-1");

        service.approve(7L, 2L);

        verify(orchestrator).executeApproved("task-1");
        verify(messageService).appendAssistant(21L, "모든 승인이 완료되어 작업을 시작합니다.");
    }

    @Test
    void rejectionCancelsTaskAndStoresAssistantMessage() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository,
                queryService,
                orchestrator,
                messageService,
                List.of(),
                mock(ResultApprovalService.class),
                taskStore
        );
        Approval approval = approval(1L, ApprovalType.CHANGE, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.reject(7L, 1L);

        verify(orchestrator).reject("task-1", 7L);
        verify(messageService).appendAssistant(
                21L,
                "작업이 거절되어 실행하지 않았습니다: 요청 작업"
        );
    }

    @Test
    void standaloneApprovalDispatchesToHandlerAndSkipsAgentPath() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        StandaloneApprovalHandler handler = mock(StandaloneApprovalHandler.class);
        when(handler.supports(ApprovalType.INFRA_OPERATION)).thenReturn(true);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(handler),
                mock(ResultApprovalService.class), taskStore
        );
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        routingFor(repository, 5L, 7L, null, ApprovalType.INFRA_OPERATION);
        when(repository.findByIdAndOwnerUserIdForUpdate(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);
        when(queryService.toResult(persisted)).thenReturn(result(persisted));

        service.approve(7L, 5L);

        verify(handler).onApproved(persisted);
        // Standalone approvals have no taskId/conversationId to drive the agent-task path with —
        // dispatching to it anyway would NPE on a null taskId, so this also doubles as a
        // regression guard for the isStandalone() branch actually short-circuiting. Also: a
        // standalone decision must never touch the task-row lock (ADR-Y1) — there is no task.
        verifyNoInteractions(orchestrator, messageService, taskStore);
    }

    @Test
    void standaloneRejectionDispatchesToHandlerAndSkipsAgentPath() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        StandaloneApprovalHandler handler = mock(StandaloneApprovalHandler.class);
        when(handler.supports(ApprovalType.INFRA_OPERATION)).thenReturn(true);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(handler),
                mock(ResultApprovalService.class), taskStore
        );
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        routingFor(repository, 5L, 7L, null, ApprovalType.INFRA_OPERATION);
        when(repository.findByIdAndOwnerUserIdForUpdate(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);
        when(queryService.toResult(persisted)).thenReturn(result(persisted));

        service.reject(7L, 5L);

        verify(handler).onRejected(persisted);
        verifyNoInteractions(orchestrator, messageService, taskStore);
    }

    @Test
    void taskBoundApprovalStillUsesAgentPathEvenWhenAStandaloneHandlerIsRegistered() {
        // Review F6: dispatch must key off routing (taskId == null), not off "is there any
        // StandaloneApprovalHandler bean present". A handler registered for INFRA_OPERATION must
        // not divert a task-bound CHANGE approval away from the agent path.
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        StandaloneApprovalHandler infraHandler = mock(StandaloneApprovalHandler.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(infraHandler),
                mock(ResultApprovalService.class), taskStore
        );
        Approval taskApproval = approval(1L, ApprovalType.CHANGE, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(taskApproval));
        when(repository.save(taskApproval)).thenReturn(taskApproval);
        when(repository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(taskApproval));
        when(queryService.toResult(taskApproval)).thenReturn(result(taskApproval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.approve(7L, 1L);

        verify(orchestrator).executeApproved("task-1");
        verify(messageService).appendAssistant(21L, "모든 승인이 완료되어 작업을 시작합니다.");
        verifyNoInteractions(infraHandler);
    }

    @Test
    void standaloneApprovalWithoutMatchingHandlerFailsWithConflict() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                mock(ResultApprovalService.class), taskStore
        );
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        routingFor(repository, 5L, 7L, null, ApprovalType.INFRA_OPERATION);
        when(repository.findByIdAndOwnerUserIdForUpdate(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);

        assertThatThrownBy(() -> service.approve(7L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standalone 승인을 처리할 핸들러가 없습니다")
                .hasMessageContaining("INFRA_OPERATION");

        verifyNoInteractions(queryService);
    }

    @Test
    void bothApproveAndRejectAcquireTheLockedLookup_notThePlainOne() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                mock(ResultApprovalService.class), taskStore
        );
        Approval approveTarget = approval(1L, ApprovalType.CHANGE, 21L);
        Approval rejectTarget = approval(2L, ApprovalType.CHANGE, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        routingFor(repository, 2L, 7L, "task-1", ApprovalType.CHANGE);
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(approveTarget));
        when(repository.findByIdAndOwnerUserIdForUpdate(2L, 7L)).thenReturn(Optional.of(rejectTarget));
        when(repository.save(approveTarget)).thenReturn(approveTarget);
        when(repository.save(rejectTarget)).thenReturn(rejectTarget);
        when(repository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(approveTarget));
        when(queryService.toResult(approveTarget)).thenReturn(result(approveTarget));
        when(queryService.toResult(rejectTarget)).thenReturn(result(rejectTarget));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.approve(7L, 1L);
        service.reject(7L, 2L);

        verify(repository).findByIdAndOwnerUserIdForUpdate(1L, 7L);
        verify(repository).findByIdAndOwnerUserIdForUpdate(2L, 7L);
        // Review F1: decision paths must never fall back to the unlocked read — that would
        // reopen the exact blind-overwrite race the locked method exists to close.
        verify(repository, never()).findByIdAndOwnerUserId(anyLong(), anyLong());
    }

    @Test
    void secondDecisionOnAnAlreadyDecidedApprovalFailsInsteadOfBlindOverwriting() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                mock(ResultApprovalService.class), taskStore
        );
        Approval approval = approval(1L, ApprovalType.CHANGE, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        // Models the two racing transactions both resolving to the same row: with the
        // PESSIMISTIC_WRITE lock in place, the second transaction's SELECT ... FOR UPDATE only
        // proceeds after the first commits — so by the time it reads, it sees the row exactly as
        // the winner left it. Reusing the same mutable `approval` instance for both calls
        // reproduces that "second reader sees the first writer's result" ordering without
        // needing real threads/transactions in a unit test.
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(repository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(approval));
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.approve(7L, 1L);

        assertThatThrownBy(() -> service.reject(7L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 처리된 승인입니다");
    }

    // ── ADR-Y1 (#55): routing lookup + task-row lock order ─────────────────────────────────────

    @Test
    void approveThrows404WithoutTakingAnyLockWhenTheApprovalDoesNotExist() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, mock(ApprovalQueryService.class), mock(AgentOrchestrator.class),
                mock(AgentMessageService.class), List.of(), mock(ResultApprovalService.class), taskStore
        );
        when(repository.findRoutingInfo(999L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(7L, 999L))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(taskStore);
        verify(repository, never()).findByIdAndOwnerUserIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void approveLocksTheTaskRowBeforeLockingTheApprovalRow() {
        // ADR-Y1 §1 / LO-1: the task row must be locked strictly before this approval's own row —
        // the exact ordering #56's original RESULT branch had inverted (#62 B3).
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, mock(AgentOrchestrator.class), mock(AgentMessageService.class),
                List.of(), mock(ResultApprovalService.class), taskStore
        );
        Approval approval = approval(1L, ApprovalType.CHANGE, 21L);
        routingFor(repository, 1L, 7L, "task-1", ApprovalType.CHANGE);
        when(repository.findByIdAndOwnerUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(repository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(approval));
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));

        service.approve(7L, 1L);

        InOrder order = inOrder(taskStore, repository);
        order.verify(taskStore).lockTask("task-1");
        order.verify(repository).findByIdAndOwnerUserIdForUpdate(1L, 7L);
        order.verify(repository).findByTaskIdOrderByIdAscForUpdate("task-1");
    }

    // ── Track Z (#56): RESULT approval branch ───────────────────────────────────────────────

    @Test
    void resultApprovalReflectsChangeResumesTaskAndNeverJoinsTheAllApprovedVote() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalService resultApprovalService = mock(ResultApprovalService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                resultApprovalService, taskStore
        );
        Approval approval = approval(9L, ApprovalType.RESULT, 21L);
        routingFor(repository, 9L, 7L, "task-1", ApprovalType.RESULT);
        when(repository.findByIdAndOwnerUserIdForUpdate(9L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(resultApprovalService.reflect(approval))
                .thenReturn(new ResultApprovalService.ReflectResult(42, "abcdef1234567"));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_RESULT_APPROVAL));

        service.approve(7L, 9L);

        verify(orchestrator).verifyResumableAfterResult("task-1");
        verify(resultApprovalService).reflect(approval);
        verify(orchestrator).resumeAfterResult("task-1");
        // ADR-Y1 (#55): the task row is now locked strictly before the RESULT approval's own row
        // and before the irreversible external merge — supersedes #56's BLOCKING-3 fix (which only
        // ordered verifyResumableAfterResult before reflect(), not the task-row lock before the
        // approval-row lock — #62 B3's flagged residual inversion).
        InOrder order = Mockito.inOrder(taskStore, repository, orchestrator, resultApprovalService);
        order.verify(taskStore).lockTask("task-1");
        order.verify(repository).findByIdAndOwnerUserIdForUpdate(9L, 7L);
        order.verify(orchestrator).verifyResumableAfterResult("task-1");
        order.verify(resultApprovalService).reflect(approval);
        order.verify(orchestrator).resumeAfterResult("task-1");
        // RESULT never joins the plan's allApproved vote (design D2/§3.3) — no
        // findByTaskIdOrderByIdAscForUpdate aggregate lookup, no executeApproved call.
        verify(repository, never()).findByTaskIdOrderByIdAscForUpdate(anyString());
        verify(orchestrator, never()).executeApproved(anyString());
        verify(messageService).appendAssistant(
                21L,
                "결과가 승인되어 main에 반영되었습니다.\n- PR: #42\n- commit: abcdef1\n남은 작업을 이어서 진행합니다."
        );
    }

    @Test
    void resultApprovalAbortsBeforeTheIrreversibleMergeWhenTheLockedPreconditionFails() {
        // BLOCKING-3 regression: models the race where the task independently moved off
        // WAITING_RESULT_APPROVAL (e.g. cancelled) between the RESULT approval's creation and
        // this approve call — the locked precondition check must reject the whole approve before
        // resultApprovalService.reflect() (the irreversible external GitHub merge) is ever
        // invoked, and before the task is ever resumed.
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalService resultApprovalService = mock(ResultApprovalService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                resultApprovalService, taskStore
        );
        Approval approval = approval(9L, ApprovalType.RESULT, 21L);
        routingFor(repository, 9L, 7L, "task-1", ApprovalType.RESULT);
        when(repository.findByIdAndOwnerUserIdForUpdate(9L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.CANCELLED));
        doThrow(new IllegalStateException("결과 승인 대기 상태가 아닌 Agent task입니다. taskId=task-1"))
                .when(orchestrator).verifyResumableAfterResult("task-1");

        assertThatThrownBy(() -> service.approve(7L, 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");

        verifyNoInteractions(resultApprovalService);
        verify(orchestrator, never()).resumeAfterResult(anyString());
        verifyNoInteractions(messageService);
    }

    @Test
    void resultRejectionMarksChangeRejectedAndCancelsTaskWithoutStandaloneDispatch() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalService resultApprovalService = mock(ResultApprovalService.class);
        StandaloneApprovalHandler handler = mock(StandaloneApprovalHandler.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(handler),
                resultApprovalService, taskStore
        );
        Approval approval = approval(9L, ApprovalType.RESULT, 21L);
        routingFor(repository, 9L, 7L, "task-1", ApprovalType.RESULT);
        when(repository.findByIdAndOwnerUserIdForUpdate(9L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_RESULT_APPROVAL));

        service.reject(7L, 9L);

        verify(resultApprovalService).markRejected(approval);
        verify(orchestrator).reject("task-1", 7L);
        verify(messageService).appendAssistant(
                21L,
                "결과가 거절되어 main에 반영하지 않았습니다. 변경은 preview 브랜치에만 남아 있습니다.\n"
                        + "이어서 수정을 요청하면 현재 preview 상태 위에서 작업합니다."
        );
        // RESULT is never standalone (taskId always set by the gate) — must not divert to a
        // registered handler even though one exists for a different type.
        verifyNoInteractions(handler);
    }

    @Test
    void resultApprovalIdempotentReflectWithNoPrStillAppendsAConfirmationMessage() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ResultApprovalService resultApprovalService = mock(ResultApprovalService.class);
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(),
                resultApprovalService, taskStore
        );
        Approval approval = approval(9L, ApprovalType.RESULT, 21L);
        routingFor(repository, 9L, 7L, "task-1", ApprovalType.RESULT);
        when(repository.findByIdAndOwnerUserIdForUpdate(9L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(queryService.toResult(approval)).thenReturn(result(approval));
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_RESULT_APPROVAL));
        // D8 idempotent no-op path: no new commits -> no PR, just main's current head SHA.
        when(resultApprovalService.reflect(approval))
                .thenReturn(new ResultApprovalService.ReflectResult(null, "1234567abcdef"));

        service.approve(7L, 9L);

        verify(messageService).appendAssistant(
                21L,
                "결과가 승인되어 main에 반영되었습니다.\n- commit: 1234567\n남은 작업을 이어서 진행합니다."
        );
    }

    private void routingFor(ApprovalRepository repository, Long approvalId, Long ownerUserId,
                            String taskId, ApprovalType type) {
        when(repository.findRoutingInfo(approvalId, ownerUserId))
                .thenReturn(Optional.of(new ApprovalRouting(taskId, type)));
    }

    private AgentTask taskWithStatus(TaskStatus status) {
        return new AgentTask("task-1", 7L, 11L, 21L, status, null, null, null, null, Instant.now());
    }

    private Approval approval(Long id, ApprovalType type, Long conversationId) {
        return new Approval(
                id,
                7L,
                11L,
                conversationId,
                "task-1",
                type,
                ApprovalStatus.PENDING,
                "요청 작업",
                LocalDateTime.now(),
                null
        );
    }

    private ApprovalResult result(Approval approval) {
        return new ApprovalResult(
                approval.getId(),
                approval.getProjectId(),
                approval.getConversationId(),
                approval.getTaskId(),
                approval.getType().name(),
                approval.getStatus().name(),
                approval.getSummary(),
                approval.getCreatedAt(),
                approval.getDecidedAt()
        );
    }
}
