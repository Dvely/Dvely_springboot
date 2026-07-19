package com.example.dvely.agent.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskFailure;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildFailureRecoveryServiceTest {

    @Test
    void createsChangeApprovalBeforeAutomaticRebuild() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator agentOrchestrator = mock(AgentOrchestrator.class);
        BuildFailureRecoveryService service = new BuildFailureRecoveryService(
                taskStore,
                approvalRepository,
                policyRepository,
                messageService,
                agentOrchestrator
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("log", "dependency 수정", 0, 3));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of());
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(
                    91L,
                    source.getOwnerUserId(),
                    source.getProjectId(),
                    source.getConversationId(),
                    source.getTaskId(),
                    source.getType(),
                    ApprovalStatus.PENDING,
                    source.getSummary(),
                    LocalDateTime.now(),
                    null
            );
        });
        CodeAgentExecutionException failure = failure();

        service.handle("task-1", failure);

        verify(taskStore).markFailed("task-1", "빌드 실패", "log", "dependency 수정");
        verify(taskStore, never()).retry("task-1", 1L);
        verify(agentOrchestrator, never()).retry(any(), any());
        verify(messageService).appendAssistant(
                21L,
                "빌드 실패\n\n수정안: dependency 수정\n\n로그 일부:\nlog"
                        + "\n\n승인 [91] 후 자동으로 수정 및 재build합니다."
        );
    }

    // HIGH-1 (retry-toctou-review.md): the auto-recovery branch must call
    // AgentOrchestrator#retry — the lock-guarded entry point every other retry() caller uses —
    // rather than TaskStore#retry directly, so this and a concurrent manual retry can never both
    // perform the transition (see OrchestrationConcurrencyIntegrationTest for the actual-thread,
    // actual-MySQL proof; this unit test only pins the wiring, not the concurrency guarantee
    // itself, which a mocked AgentOrchestrator cannot exercise).
    @Test
    void retriesImmediatelyWhenProjectPolicyDisablesChangeApproval() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator agentOrchestrator = mock(AgentOrchestrator.class);
        BuildFailureRecoveryService service = new BuildFailureRecoveryService(
                taskStore,
                approvalRepository,
                policyRepository,
                messageService,
                agentOrchestrator
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("log", "dependency 수정", 0, 3));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectApprovalPolicy(11L, false, true, true, true)
        ));
        when(agentOrchestrator.retry("task-1", 1L)).thenReturn(true);

        service.handle("task-1", failure());

        verify(agentOrchestrator).retry("task-1", 1L);
        verify(taskStore, never()).retry(any(), any());
        verify(approvalRepository, never()).save(any());
        verify(messageService).appendAssistant(
                21L,
                "빌드 실패\n\n수정안: dependency 수정\n\n로그 일부:\nlog"
                        + "\n\n프로젝트 정책에 따라 자동 재시도를 시작합니다."
        );
    }

    // HIGH-1 follow-up: when AgentOrchestrator#retry reports it lost the task-row lock race to a
    // concurrent actor (e.g. the user's own manual POST /retry), this must be a silent no-op —
    // no misleading "자동 재시도를 시작합니다" message for a retry this call did not actually
    // perform.
    @Test
    void appendsNoMessageWhenAutomaticRetryLosesTheTaskLockRaceToAConcurrentActor() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator agentOrchestrator = mock(AgentOrchestrator.class);
        BuildFailureRecoveryService service = new BuildFailureRecoveryService(
                taskStore,
                approvalRepository,
                policyRepository,
                messageService,
                agentOrchestrator
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("log", "dependency 수정", 0, 3));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectApprovalPolicy(11L, false, true, true, true)
        ));
        when(agentOrchestrator.retry("task-1", 1L)).thenReturn(false);

        service.handle("task-1", failure());

        verify(agentOrchestrator).retry("task-1", 1L);
        verify(messageService, never()).appendAssistant(any(), any());
    }

    private CodeAgentExecutionException failure() {
        return new CodeAgentExecutionException(
                "빌드 실패",
                "log",
                "dependency 수정",
                new IllegalStateException("build")
        );
    }

    private AgentTask task() {
        return new AgentTask(
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
        );
    }
}
