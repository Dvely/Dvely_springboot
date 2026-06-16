package com.example.dvely.approval.application.command;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
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
                messageService
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
                messageService
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
