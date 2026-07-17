package com.example.dvely.approval.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.approval.application.port.out.StandaloneApprovalHandler;
import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalCommandServiceTest {

    @Test
    void executesTaskOnlyAfterEveryApprovalIsApproved() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository,
                queryService,
                orchestrator,
                messageService,
                List.of()
        );
        Approval change = approval(1L, ApprovalType.CHANGE, 21L);
        Approval deployment = approval(2L, ApprovalType.DEPLOYMENT, 21L);
        when(repository.findByIdAndOwnerUserId(1L, 7L)).thenReturn(Optional.of(change));
        when(repository.findByIdAndOwnerUserId(2L, 7L)).thenReturn(Optional.of(deployment));
        when(repository.save(change)).thenReturn(change);
        when(repository.save(deployment)).thenReturn(deployment);
        when(repository.findByTaskIdOrderByIdAsc("task-1"))
                .thenReturn(List.of(change, deployment));
        when(queryService.toResult(change)).thenReturn(result(change));
        when(queryService.toResult(deployment)).thenReturn(result(deployment));

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
        ApprovalCommandService service = new ApprovalCommandService(
                repository,
                queryService,
                orchestrator,
                messageService,
                List.of()
        );
        Approval approval = approval(1L, ApprovalType.CHANGE, 21L);
        when(repository.findByIdAndOwnerUserId(1L, 7L)).thenReturn(Optional.of(approval));
        when(repository.save(approval)).thenReturn(approval);
        when(queryService.toResult(approval)).thenReturn(result(approval));

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
        StandaloneApprovalHandler handler = mock(StandaloneApprovalHandler.class);
        when(handler.supports(ApprovalType.INFRA_OPERATION)).thenReturn(true);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(handler)
        );
        Approval standalone = Approval.standalone(7L, 11L, ApprovalType.INFRA_OPERATION, "인프라 설정 변경 요청");
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        when(repository.findByIdAndOwnerUserId(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);
        when(queryService.toResult(persisted)).thenReturn(result(persisted));

        service.approve(7L, 5L);

        verify(handler).onApproved(persisted);
        // Standalone approvals have no taskId/conversationId to drive the agent-task path with —
        // dispatching to it anyway would NPE on a null taskId, so this also doubles as a
        // regression guard for the isStandalone() branch actually short-circuiting.
        verifyNoInteractions(orchestrator, messageService);
    }

    @Test
    void standaloneRejectionDispatchesToHandlerAndSkipsAgentPath() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        StandaloneApprovalHandler handler = mock(StandaloneApprovalHandler.class);
        when(handler.supports(ApprovalType.INFRA_OPERATION)).thenReturn(true);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of(handler)
        );
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        when(repository.findByIdAndOwnerUserId(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);
        when(queryService.toResult(persisted)).thenReturn(result(persisted));

        service.reject(7L, 5L);

        verify(handler).onRejected(persisted);
        verifyNoInteractions(orchestrator, messageService);
    }

    @Test
    void standaloneApprovalWithoutMatchingHandlerFailsWithConflict() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        ApprovalQueryService queryService = mock(ApprovalQueryService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        ApprovalCommandService service = new ApprovalCommandService(
                repository, queryService, orchestrator, messageService, List.of()
        );
        Approval persisted = new Approval(
                5L, 7L, 11L, null, null, ApprovalType.INFRA_OPERATION,
                ApprovalStatus.PENDING, "인프라 설정 변경 요청", LocalDateTime.now(), null
        );
        when(repository.findByIdAndOwnerUserId(5L, 7L)).thenReturn(Optional.of(persisted));
        when(repository.save(persisted)).thenReturn(persisted);

        assertThatThrownBy(() -> service.approve(7L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standalone 승인을 처리할 핸들러가 없습니다")
                .hasMessageContaining("INFRA_OPERATION");

        verifyNoInteractions(queryService);
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
