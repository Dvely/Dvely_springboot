package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentOrchestratorTest {

    @Test
    void waitsForAllRequiredApprovalsBeforeQueueing() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                conversationRepository,
                policyRepository,
                approvalRepository,
                messageService
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(approvalRepository.save(any(Approval.class)))
                .thenAnswer(invocation -> {
                    Approval source = invocation.getArgument(0);
                    long id = source.getType() == ApprovalType.CHANGE ? 101L : 102L;
                    return new Approval(
                            id,
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
        AgentPlan plan = new AgentPlan(
                List.of(
                        new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ를 추가한다")),
                        new AgentStep(AgentType.DEPLOY, Map.of("instruction", "최신 버전을 배포한다"))
                ),
                "reason",
                AiProvider.OPENAI,
                11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(submission.approvalIds()).containsExactly(101L, 102L);
        verify(taskStore, never()).enqueue(submission.taskId());
        verify(taskStore).markWaitingApproval(
                submission.taskId(),
                "작업 계획을 만들었습니다. 승인 후 실행합니다.\n"
                        + "- [101] CHANGE: FAQ를 추가한다\n"
                        + "- [102] DEPLOYMENT: 최신 버전을 배포한다"
        );
        verify(messageService).appendAssistant(
                null,
                "작업 계획을 만들었습니다. 승인 후 실행합니다.\n"
                        + "- [101] CHANGE: FAQ를 추가한다\n"
                        + "- [102] DEPLOYMENT: 최신 버전을 배포한다"
        );
    }

    @Test
    void storesContextAndQueuesWhenPolicyDoesNotRequireApproval() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                conversationRepository,
                policyRepository,
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.of(
                new ProjectApprovalPolicy(11L, false, false, false, false)
        ));
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정한다"))),
                "reason",
                AiProvider.OPENAI,
                null
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, 21L);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskStore).save(taskCaptor.capture());
        AgentTask task = taskCaptor.getValue();
        assertThat(task.ownerUserId()).isEqualTo(1L);
        assertThat(task.projectId()).isEqualTo(11L);
        assertThat(task.conversationId()).isEqualTo(21L);
        assertThat(submission.status()).isEqualTo(TaskStatus.QUEUED);
        verify(taskStore).savePlan(
                submission.taskId(),
                new AgentPlan(plan.steps(), "reason", AiProvider.OPENAI, 11L)
        );
        verify(taskStore).enqueue(submission.taskId());
    }

    @Test
    void resolvesConversationProjectBeforeDecision() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(TaskStore.class),
                projectRepository,
                conversationRepository,
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));

        assertThat(orchestrator.resolveProjectId(1L, null, 21L)).isEqualTo(11L);
    }

    @Test
    void cancellingATaskWaitingOnAResultApprovalAlsoCancelsThatPendingApproval() {
        // §4.4 edge "대기 중 task 취소": the existing generic cancel machine (design D3) already
        // covers RESULT — no type-specific branch needed, this just closes the loop with an
        // explicit RESULT-typed regression guard.
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class)
        );
        Approval resultApproval = new Approval(
                91L, 1L, 11L, 21L, "task-1", ApprovalType.RESULT,
                ApprovalStatus.PENDING, "[결과 반영] 요약", LocalDateTime.now(), null
        );
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of(resultApproval));

        assertThat(orchestrator.cancel("task-1", 1L)).isTrue();

        assertThat(resultApproval.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(approvalRepository).save(resultApproval);
    }

    // ── Track Z (#56): resumeAfterResult — WAITING_RESULT_APPROVAL -> QUEUED resume gate ──────

    @Test
    void resumeAfterResultRequeuesAWaitingResultApprovalTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.resumeAfterResultApproval("task-1")).thenReturn(true);

        orchestrator.resumeAfterResult("task-1");

        verify(taskStore).resumeAfterResultApproval("task-1");
    }

    @Test
    void resumeAfterResultThrowsConflictWhenTaskIsNotWaitingForResultApproval() {
        // E-RA-03: guards a racing duplicate approve (or an approve arriving after the task was
        // independently cancelled) — must fail loudly (-> 409) rather than silently no-op.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.resumeAfterResultApproval("task-1")).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.resumeAfterResult("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");
    }

    @Test
    void cancellingTaskAlsoCancelsPendingApprovals() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class)
        );
        Approval approval = new Approval(
                91L,
                1L,
                11L,
                21L,
                "task-1",
                ApprovalType.CHANGE,
                ApprovalStatus.PENDING,
                "변경 승인",
                LocalDateTime.now(),
                null
        );
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of(approval));

        assertThat(orchestrator.cancel("task-1", 1L)).isTrue();

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(approvalRepository).save(approval);
    }

    // ── Cloud Ops Agent (INFRA_OPERATE) approval gating — design doc D4/D7 ─────────────────────

    @Test
    void infraOperateRestartWithPolicyOnCreatesInfraOperationApprovalWithServiceImpactMarker() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                mock(ConversationRepository.class),
                policyRepository,
                approvalRepository,
                mock(AgentMessageService.class)
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        // Empty policy -> ProjectApprovalPolicy's fail-safe default (all required, including infra).
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(
                    201L, source.getOwnerUserId(), source.getProjectId(), source.getConversationId(),
                    source.getTaskId(), source.getType(), ApprovalStatus.PENDING, source.getSummary(),
                    LocalDateTime.now(), null
            );
        });
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.INFRA_OPERATE,
                        Map.of("operation", "RESTART", "instruction", "preview 서버를 재시작해줘"))),
                "reason", AiProvider.ANTHROPIC, 11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(submission.approvalIds()).containsExactly(201L);
        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ApprovalType.INFRA_OPERATION);
        assertThat(captor.getValue().getSummary())
                .startsWith("[서비스 영향]")
                .contains("preview 서버를 재시작해줘");
    }

    @Test
    void infraOperateRestartWithPolicyOffSkipsApprovalAndQueuesImmediately() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                mock(ConversationRepository.class),
                policyRepository,
                approvalRepository,
                mock(AgentMessageService.class)
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        // infraApprovalRequired=false — user turned the policy off (User Sovereignty, design D4).
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, false)));
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"))),
                "reason", AiProvider.ANTHROPIC, 11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(submission.approvalIds()).isEmpty();
        verify(approvalRepository, never()).save(any());
        verify(taskStore).enqueue(submission.taskId());
    }

    @Test
    void infraOperateReadOnlyOperationNeverRequiresApproval() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                mock(ConversationRepository.class),
                policyRepository,
                approvalRepository,
                mock(AgentMessageService.class)
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        // Policy fully ON — STATUS_CHECK is still not an approval target (PRD §21.3: read-only
        // operations are simply out of scope for the approval gate, regardless of policy).
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"))),
                "reason", AiProvider.ANTHROPIC, 11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.QUEUED);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void infraOperateWithUnidentifiedOperationSkipsApprovalAndLetsExecutorRespondWithGuidance() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                projectRepository,
                mock(ConversationRepository.class),
                policyRepository,
                approvalRepository,
                mock(AgentMessageService.class)
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());
        // Missing/garbled "operation" — InfraOperation.parse() returns empty, so the catalog
        // whitelist boundary (design D3) rules this out of the approval gate entirely.
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.INFRA_OPERATE, Map.of("instruction", "뭔가 이상한 요청"))),
                "reason", AiProvider.ANTHROPIC, 11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.status()).isEqualTo(TaskStatus.QUEUED);
        verify(approvalRepository, never()).save(any());
    }
}
