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
        BuildFailureRecoveryService service = new BuildFailureRecoveryService(
                taskStore,
                approvalRepository,
                policyRepository,
                messageService
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
        verify(messageService).appendAssistant(
                21L,
                "빌드 실패\n\n수정안: dependency 수정\n\n로그 일부:\nlog"
                        + "\n\n승인 [91] 후 자동으로 수정 및 재build합니다."
        );
    }

    @Test
    void retriesImmediatelyWhenProjectPolicyDisablesChangeApproval() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        BuildFailureRecoveryService service = new BuildFailureRecoveryService(
                taskStore,
                approvalRepository,
                policyRepository,
                mock(AgentMessageService.class)
        );
        when(taskStore.get("task-1")).thenReturn(task());
        when(taskStore.getFailure("task-1", 1L))
                .thenReturn(new AgentTaskFailure("log", "dependency 수정", 0, 3));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectApprovalPolicy(11L, false, true, true, true)
        ));

        service.handle("task-1", failure());

        verify(taskStore).retry("task-1", 1L);
        verify(approvalRepository, never()).save(any());
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
