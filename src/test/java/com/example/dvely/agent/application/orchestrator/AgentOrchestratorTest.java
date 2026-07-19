package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(resultApproval));

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

    // ── Review follow-up (BLOCKING-3): verifyResumableAfterResult — the locked precondition check
    // that must run BEFORE ResultApprovalService#reflect()'s irreversible GitHub merge. ──────────

    @Test
    void verifyResumableAfterResultDelegatesToTaskStoresLockedGuard() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );

        orchestrator.verifyResumableAfterResult("task-1");

        verify(taskStore).requireWaitingResultApproval("task-1");
    }

    @Test
    void verifyResumableAfterResultPropagatesTaskStoresConflict() {
        // A racing cancel/duplicate-approve makes the locked precondition fail — this must
        // surface as a thrown exception (-> 409) so the caller (ApprovalCommandService) never
        // proceeds to the irreversible external merge.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "결과 승인 대기 상태가 아닌 Agent task입니다. taskId=task-1"))
                .when(taskStore).requireWaitingResultApproval("task-1");

        assertThatThrownBy(() -> orchestrator.verifyResumableAfterResult("task-1"))
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
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(approval));

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

    // ── Y6-a (#55): reject cascades to sibling PENDING approvals, symmetric with cancel ────────

    @Test
    void rejectCancelsTheTaskAndCascadesToSiblingPendingApprovals() {
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
        Approval siblingPending = new Approval(
                92L, 1L, 11L, 21L, "task-1", ApprovalType.DEPLOYMENT,
                ApprovalStatus.PENDING, "배포 승인", LocalDateTime.now(), null
        );
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(siblingPending));

        orchestrator.reject("task-1", 1L);

        // G6 regression guard: reject used to leave sibling PENDING approvals untouched — now it
        // must cancel them exactly the same way user cancel already did (Y6-a symmetry).
        assertThat(siblingPending.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(approvalRepository).save(siblingPending);
    }

    @Test
    void rejectThrowsWhenTheUnderlyingTaskCancelFails() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.cancel("task-1", 1L)).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.reject("task-1", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");
    }

    @Test
    void cancelAndRejectShareTheSameCascadeSoAnApproveAfterEitherIsRejectedByThePendingGuard() {
        // Structural guarantee (design invariant "task 터미널 ⇒ PENDING 승인 없음"): both entry points
        // must funnel through the identical cascade, not two independently-maintained copies that
        // could drift. Asserted here by checking both leave the same sibling CANCELLED via the
        // same repository calls, rather than merely inferring it from the production code shape.
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
        Approval sibling = new Approval(
                93L, 1L, 11L, 21L, "task-1", ApprovalType.DOMAIN_BINDING,
                ApprovalStatus.PENDING, "도메인 승인", LocalDateTime.now(), null
        );
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(sibling));

        orchestrator.cancel("task-1", 1L);

        assertThat(sibling.getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
    }

    // ── #57 (QA report §5.6/H1/M3): retry() / findPendingApprovalId() shared judgment ───────────

    @Test
    void retryDelegatesToTaskStoreWhenNoApprovalIsPending() {
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
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of());
        when(taskStore.retry("task-1", 1L)).thenReturn(true);

        assertThat(orchestrator.retry("task-1", 1L)).isTrue();
        verify(taskStore).retry("task-1", 1L);
    }

    @Test
    void retryRefusesWithoutEvenAskingTaskStoreWhenAnApprovalIsStillPending() {
        // The QA-reported drift (H1) was exactly this: attempt<maxAttempts alone said "retryable"
        // while this method already refused whenever a PENDING approval — e.g.
        // BuildFailureRecoveryService's "자동 수정 및 재build" CHANGE approval — was still open.
        // taskStore.retry must never even be consulted once a PENDING approval is found (retry()
        // short-circuits on findPendingApprovalId, not on taskStore's own attempt/status check).
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
        Approval recoveryApproval = new Approval(
                55L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.PENDING, "자동 수정 및 재build: 의존성 설치 후 재빌드", LocalDateTime.now(), null
        );
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of(recoveryApproval));

        assertThat(orchestrator.retry("task-1", 1L)).isFalse();
        verify(taskStore, never()).retry(anyString(), anyLong());
    }

    @Test
    void findPendingApprovalIdReturnsNullWhenEveryApprovalOnTheTaskIsAlreadyDecided() {
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(TaskStore.class),
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class)
        );
        Approval decided = new Approval(
                61L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.APPROVED, "요약", LocalDateTime.now(), LocalDateTime.now()
        );
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of(decided));

        assertThat(orchestrator.findPendingApprovalId("task-1")).isNull();
    }

    @Test
    void findPendingApprovalIdReturnsTheOldestPendingApprovalIdAmongMultiple() {
        // LO-1 (id-ascending): with more than one PENDING approval outstanding, the oldest one is
        // the one surfaced to the task screen — matches the order the plan-approval flow already
        // presents approvals in (submit()'s approvalIds list).
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(TaskStore.class),
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class)
        );
        Approval decided = new Approval(
                60L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.APPROVED, "요약1", LocalDateTime.now(), LocalDateTime.now()
        );
        Approval firstPending = new Approval(
                61L, 1L, 11L, 21L, "task-1", ApprovalType.DEPLOYMENT,
                ApprovalStatus.PENDING, "요약2", LocalDateTime.now(), null
        );
        Approval secondPending = new Approval(
                62L, 1L, 11L, 21L, "task-1", ApprovalType.DOMAIN_BINDING,
                ApprovalStatus.PENDING, "요약3", LocalDateTime.now(), null
        );
        when(approvalRepository.findByTaskIdOrderByIdAsc("task-1"))
                .thenReturn(List.of(decided, firstPending, secondPending));

        assertThat(orchestrator.findPendingApprovalId("task-1")).isEqualTo(61L);
    }

    // ── ADR-Y1 §1 step⑥-plan guard (#55): executeApproved state guard ──────────────────────────

    @Test
    void executeApprovedEnqueuesAWaitingApprovalTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.get("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));
        when(taskStore.getPlan("task-1")).thenReturn(new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L));

        orchestrator.executeApproved("task-1");

        verify(taskStore).enqueue("task-1");
    }

    @Test
    void executeApprovedRetriesAFailedTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.get("task-1")).thenReturn(taskWithStatus(TaskStatus.FAILED));
        when(taskStore.getPlan("task-1")).thenReturn(new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L));
        when(taskStore.retry("task-1", 1L)).thenReturn(true);

        orchestrator.executeApproved("task-1");

        verify(taskStore).retry("task-1", 1L);
        verify(taskStore, never()).enqueue("task-1");
    }

    @Test
    void executeApprovedRejectsAnyOtherStatusWithConflict() {
        // Guards against a caller/state-machine bug reaching this method with a status that is
        // neither WAITING_APPROVAL nor FAILED — e.g. a duplicate/racing call landing after the
        // task already moved on. Under ADR-Y1 the caller always holds the task lock by this point,
        // so this is never a legitimate race — a status guard turning it into 409 is the correct
        // response, not a silent no-op or an accidental double-enqueue.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.get("task-1")).thenReturn(taskWithStatus(TaskStatus.RUNNING));
        when(taskStore.getPlan("task-1")).thenReturn(new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L));

        assertThatThrownBy(() -> orchestrator.executeApproved("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");

        verify(taskStore, never()).enqueue(anyString());
        verify(taskStore, never()).retry(anyString(), anyLong());
    }

    // ── ADR-Y2 (#55): recoverStuckApprovedTask — the sweep's lock-and-reverify step ─────────────

    @Test
    void recoverStuckApprovedTaskRecoversWhenEveryApprovalIsApproved() {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                messageService
        );
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));
        Approval change = new Approval(1L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.APPROVED, "요약1", LocalDateTime.now(), LocalDateTime.now());
        Approval deployment = new Approval(2L, 1L, 11L, 21L, "task-1", ApprovalType.DEPLOYMENT,
                ApprovalStatus.APPROVED, "요약2", LocalDateTime.now(), LocalDateTime.now());
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1"))
                .thenReturn(List.of(change, deployment));

        orchestrator.recoverStuckApprovedTask("task-1");

        verify(taskStore).recoverStuckApproval("task-1");
        verify(messageService).appendAssistant(21L, "지연된 승인 처리를 복구해 작업을 시작합니다.");
    }

    @Test
    void recoverStuckApprovedTaskNoOpsWhenATaskIsNotActuallyWaitingApproval() {
        // A racing approve/reject/cancel already resolved the task between the sweep's candidate
        // read and this call acquiring the lock — must be a silent no-op, never a double
        // transition.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class)
        );
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.QUEUED));

        orchestrator.recoverStuckApprovedTask("task-1");

        verify(taskStore, never()).recoverStuckApproval(anyString());
    }

    @Test
    void recoverStuckApprovedTaskNoOpsWhenAnApprovalIsStillPending() {
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
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));
        Approval change = new Approval(1L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.APPROVED, "요약1", LocalDateTime.now(), LocalDateTime.now());
        Approval pendingDeployment = new Approval(2L, 1L, 11L, 21L, "task-1", ApprovalType.DEPLOYMENT,
                ApprovalStatus.PENDING, "요약2", LocalDateTime.now(), null);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1"))
                .thenReturn(List.of(change, pendingDeployment));

        orchestrator.recoverStuckApprovedTask("task-1");

        verify(taskStore, never()).recoverStuckApproval(anyString());
    }

    @Test
    void recoverStuckApprovedTaskNoOpsWhenThereAreNoApprovalsAtAll() {
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
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        orchestrator.recoverStuckApprovedTask("task-1");

        verify(taskStore, never()).recoverStuckApproval(anyString());
    }

    // ── Y6-b (#55): dedupe summary merge — multiple steps of the same type ─────────────────────

    @Test
    void multipleCodeStepsMergeIntoOneNumberedChangeApprovalSummary() {
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
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(
                    301L, source.getOwnerUserId(), source.getProjectId(), source.getConversationId(),
                    source.getTaskId(), source.getType(), ApprovalStatus.PENDING, source.getSummary(),
                    LocalDateTime.now(), null
            );
        });
        AgentPlan plan = new AgentPlan(
                List.of(
                        new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ 페이지를 추가한다")),
                        new AgentStep(AgentType.CODE, Map.of("instruction", "네비게이션 바를 수정한다"))
                ),
                "reason", AiProvider.OPENAI, 11L
        );

        AgentSubmission submission = orchestrator.submit(plan, 1L, null);

        assertThat(submission.approvalIds()).containsExactly(301L);
        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ApprovalType.CHANGE);
        // G8 regression guard: the old putIfAbsent kept only the first step's summary — both must
        // now be present, numbered.
        assertThat(captor.getValue().getSummary())
                .isEqualTo("1) FAQ 페이지를 추가한다\n2) 네비게이션 바를 수정한다");
    }

    @Test
    void singleStepPerTypeSummaryStaysUnnumbered() {
        // Behavior-preserving for the common (and pre-existing-test-covered) case: a lone step of
        // a type must not gain a "1) " prefix it never had before Y6-b.
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
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(
                    301L, source.getOwnerUserId(), source.getProjectId(), source.getConversationId(),
                    source.getTaskId(), source.getType(), ApprovalStatus.PENDING, source.getSummary(),
                    LocalDateTime.now(), null
            );
        });
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ 페이지를 추가한다"))),
                "reason", AiProvider.OPENAI, 11L
        );

        orchestrator.submit(plan, 1L, null);

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getSummary()).isEqualTo("FAQ 페이지를 추가한다");
    }

    private AgentTask taskWithStatus(TaskStatus status) {
        return new AgentTask("task-1", 1L, 11L, 21L, status, null, null, null, null, java.time.Instant.now());
    }
}
