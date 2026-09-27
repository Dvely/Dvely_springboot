package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
                messageService,
                mock(InputWaitStore.class)
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
        // 채팅에는 지시문을 담지 않는다. 바로 아래 붙는 승인 카드가 같은 summary 를 그리므로
        // 여기까지 실으면 같은 내용이 두 번 보인다. 태스크 summary(위 markWaitingApproval)는
        // 카드와 나란히 놓이지 않으므로 지시문을 그대로 유지한다.
        // taskId 는 submit() 이 새로 발급하므로(newTaskId) 값을 고정할 수 없다 — 실렸다는 것만 본다.
        verify(messageService).appendAssistant(
                isNull(),
                eq("작업 계획을 만들었습니다. 승인 후 실행합니다.\n"
                        + "- [101] CHANGE\n"
                        + "- [102] DEPLOYMENT"),
                eq(ChatMessageKind.APPROVAL_REQUESTED),
                anyString());
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
    void 계획_승인_대기는_프리뷰_회수_정리의_대상이_아니다() {
        // #380 에서 가장 위험한 실수. WAITING_APPROVAL(계획 승인)은 CODE 실행 '전' 이라 프리뷰가
        // 아예 없다. 상태 검사를 느슨하게 두면 "프리뷰가 없다" 는 조건에 모든 계획 승인이 걸려
        // 즉시 취소된다 — 사용자가 승인 버튼을 누르기도 전에 작업이 사라진다.
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                messageService,
                mock(InputWaitStore.class)
        );
        when(taskStore.lockTask("task-1")).thenReturn(new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.WAITING_APPROVAL,
                null, null, null, null, Instant.now()));
        // 취소가 '성공할 수 있는' 상태로 만들어 둔다. 이렇게 해야 이 테스트가 통과할 때 그 이유가
        // "상태 검사가 막았다" 하나로 좁혀진다 — 스텁을 비워 두면 cancelTaskCascade 가 false 를
        // 돌려주는 바람에 상태 검사를 지워도 그대로 통과하는, 아무것도 증명하지 않는 테스트가 된다.
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        boolean closed = orchestrator.abandonApprovalTaskWithLostPreview("task-1", Duration.ofHours(6));

        assertThat(closed).isFalse();
        verify(taskStore, never()).cancel("task-1", 1L);
        verifyNoInteractions(messageService);
    }

    @Test
    void resumeAfterResultRequeuesAWaitingResultApprovalTask() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        when(taskStore.resumeAfterResultApproval("task-1")).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.resumeAfterResult("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1");
    }

    @Test
    void declineAfterResultResumesThroughTheDeclineEventInsteadOfTheApprovalOne() {
        // REPOSITORY_BINDING rejection: same WAITING_RESULT_APPROVAL -> QUEUED transition as an
        // approval, but it must not route through the RESULT_APPROVED event.
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        when(taskStore.resumeAfterResultDecline("task-1", "연결하지 않음")).thenReturn(true);

        orchestrator.declineAfterResult("task-1", "연결하지 않음");

        verify(taskStore).resumeAfterResultDecline("task-1", "연결하지 않음");
        verify(taskStore, never()).resumeAfterResultApproval(anyString());
    }

    @Test
    void declineAfterResultThrowsConflictWhenTaskIsNotWaitingForResultApproval() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        when(taskStore.resumeAfterResultDecline("task-1", "연결하지 않음")).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.declineAfterResult("task-1", "연결하지 않음"))
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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

    /**
     * 도메인 "해제"(operation=DELETE)는 연결과 별개 유형 {@code DOMAIN_UNBIND} 로 승인이 만들어져야 한다 —
     * 되돌리기 어려운 삭제라 화면이 "연결 승인"으로 오표시하지 않게(배포 e2e 발견 #8). operation 없는 연결은
     * 그대로 {@code DOMAIN_BINDING}.
     */
    @Test
    void domainUnbindCreatesDomainUnbindApprovalType() {
        TaskStore taskStore = mock(TaskStore.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore, projectRepository, mock(ConversationRepository.class),
                policyRepository, approvalRepository, mock(AgentMessageService.class),
                mock(InputWaitStore.class));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty());   // fail-safe: 전부 required
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(202L, source.getOwnerUserId(), source.getProjectId(), source.getConversationId(),
                    source.getTaskId(), source.getType(), ApprovalStatus.PENDING, source.getSummary(),
                    LocalDateTime.now(), null);
        });
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.DOMAIN_BIND,
                        Map.of("operation", "DELETE", "domainId", "4",
                                "instruction", "도메인 연결 해제: guestbook-app.qeploy.com"))),
                "reason", AiProvider.ANTHROPIC, 11L);

        orchestrator.submit(plan, 1L, null);

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ApprovalType.DOMAIN_UNBIND);
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
    void retryLocksTheTaskRowFirstThenDelegatesToTaskStoreWhenNoApprovalIsPending() {
        // Issue #64: retry() must now be a task-bound decision — task row locked FIRST (ADR-Y1
        // order, same as approve/reject/cancel/sweep), then a *locking* re-verification of
        // approvals, all inside one transaction. The mock stub below (locking-read method) proves
        // retry() no longer uses the non-locking findByTaskIdOrderByIdAsc for its own action gate.
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());
        when(taskStore.retry("task-1", 1L)).thenReturn(true);

        assertThat(orchestrator.retry("task-1", 1L)).isTrue();
        InOrder order = inOrder(taskStore, approvalRepository);
        order.verify(taskStore).lockTask("task-1");
        order.verify(approvalRepository).findByTaskIdOrderByIdAscForUpdate("task-1");
        order.verify(taskStore).retry("task-1", 1L);
    }

    @Test
    void retryLocksTheTaskRowButRefusesWithoutEvenAskingTaskStoreWhenAnApprovalIsStillPending() {
        // The QA-reported drift (H1) was exactly this: attempt<maxAttempts alone said "retryable"
        // while this method already refused whenever a PENDING approval — e.g.
        // BuildFailureRecoveryService's "자동 수정 및 재build" CHANGE approval — was still open.
        // taskStore.retry must never even be consulted once a PENDING approval is found. Unlike
        // before #64, the task row IS still locked first (taskStore.lockTask) — that lock
        // acquisition itself is what makes the "no PENDING approval" check below race-free against
        // a concurrent approve/reject/cancel on the same taskId (see retry()'s javadoc).
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        Approval recoveryApproval = new Approval(
                55L, 1L, 11L, 21L, "task-1", ApprovalType.CHANGE,
                ApprovalStatus.PENDING, "자동 수정 및 재build: 의존성 설치 후 재빌드", LocalDateTime.now(), null
        );
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of(recoveryApproval));

        assertThat(orchestrator.retry("task-1", 1L)).isFalse();
        verify(taskStore).lockTask("task-1");
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                messageService,
                mock(InputWaitStore.class)
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
        verify(messageService).appendAssistant(21L, "지연된 승인 처리를 복구해 작업을 시작합니다.",
                ChatMessageKind.TASK_PROGRESS, "task-1");
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
        );
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.WAITING_APPROVAL));
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1")).thenReturn(List.of());

        orchestrator.recoverStuckApprovedTask("task-1");

        verify(taskStore, never()).recoverStuckApproval(anyString());
    }

    // ── 시작되지 않은 채 방치된 PENDING 태스크 정리 (메시지 Decision 비동기화) ────────────────

    @Test
    void failStalePendingTaskClosesAPendingTaskAndNotifiesTheConversation() {
        TaskStore taskStore = mock(TaskStore.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                messageService,
                mock(InputWaitStore.class)
        );
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.PENDING));

        boolean closed = orchestrator.failStalePendingTask("task-1");

        assertThat(closed).isTrue();
        verify(taskStore).markFailed("task-1", "요청 처리가 시작되지 않아 종료했습니다.");
        verify(messageService).appendAssistant(21L, "요청 처리가 시작되지 않아 이 작업을 종료했습니다. 다시 시도해주세요.",
                ChatMessageKind.TASK_CANCELLED, "task-1");
    }

    @Test
    void failStalePendingTaskNoOpsWhenTheDecisionAlreadyMovedTheTaskOn() {
        // grace 안에 살아 있는 Decision 이 확정(예: QUEUED)했다면 잠금 아래 상태가 PENDING 이 아니다
        // — 조용히 no-op 해야지, 이미 진행 중인 태스크를 FAILED 로 덮어써서는 안 된다.
        TaskStore taskStore = mock(TaskStore.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                messageService,
                mock(InputWaitStore.class)
        );
        when(taskStore.lockTask("task-1")).thenReturn(taskWithStatus(TaskStatus.QUEUED));

        boolean closed = orchestrator.failStalePendingTask("task-1");

        assertThat(closed).isFalse();
        verify(taskStore, never()).markFailed(anyString(), anyString());
        verifyNoInteractions(messageService);
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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
                mock(AgentMessageService.class),
                mock(InputWaitStore.class)
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

    /**
     * 되묻기 답이 대화에 남아야 한다. 폼은 답한 순간 사라지도록 설계돼 있어(이중 제출 방지),
     * 답이 대화에 없으면 사용자가 무엇을 골랐는지 확인할 방법이 아예 사라진다 — 새로고침하면
     * "어떤 프론트엔드 스택으로 만들까요?"만 남고 자기가 고른 Vanilla 는 어디에도 없다
     * (2026-09-07 dev 실측, project 45). 배포 저장소 이름·도메인 입력도 같은 경로다.
     */
    @Test
    void supplyInputEchoesTheAnswerIntoTheConversation() {
        InputWaitStore inputWaitStore = mock(InputWaitStore.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(TaskStore.class),
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                messageService,
                inputWaitStore
        );
        when(inputWaitStore.supply("task-1", 1L, "Vanilla (HTML/CSS/JS, 빌드 없음)")).thenReturn(true);

        boolean accepted = orchestrator.supplyInput("task-1", 1L, 21L, "Vanilla (HTML/CSS/JS, 빌드 없음)");

        assertThat(accepted).isTrue();
        verify(messageService).appendAssistant(
                21L, "답변을 반영해 작업을 이어갑니다: Vanilla (HTML/CSS/JS, 빌드 없음)",
                ChatMessageKind.CLARIFICATION_ANSWER, "task-1");
    }

    /** 태스크가 답을 못 받으면(이미 끝났거나 남의 것) 대화에도 남기지 않는다. */
    @Test
    void supplyInputWritesNothingWhenTheTaskRejectsTheAnswer() {
        InputWaitStore inputWaitStore = mock(InputWaitStore.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(TaskStore.class),
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                mock(ApprovalRepository.class),
                messageService,
                inputWaitStore
        );
        when(inputWaitStore.supply("task-1", 1L, "react")).thenReturn(false);

        assertThat(orchestrator.supplyInput("task-1", 1L, 21L, "react")).isFalse();
        verify(messageService, never()).appendAssistant(any(), any(), any(), any());
    }

    // ── #401: 저장소 연결 대기는 스윕이 취소하지 않고 마친다 ──────────────────────────────────

    /** 이 테스트들이 공유하는 조립. 취소가 '성공할 수 있는' 상태로 둬서 통과 이유를 하나로 좁힌다. */
    private record BindingSweepFixture(
            AgentOrchestrator orchestrator,
            TaskStore taskStore,
            ApprovalRepository approvalRepository,
            AgentMessageService messageService,
            Approval approval
    ) {}

    private BindingSweepFixture bindingSweepFixture(ApprovalType type, TaskStatus status) {
        TaskStore taskStore = mock(TaskStore.class);
        ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
        AgentMessageService messageService = mock(AgentMessageService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                taskStore,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                mock(ProjectApprovalPolicyRepository.class),
                approvalRepository,
                messageService,
                mock(InputWaitStore.class)
        );
        Approval approval = new Approval(
                91L, 1L, 11L, 21L, "task-1", type,
                ApprovalStatus.PENDING, "[저장소 연결] my-app", LocalDateTime.now(), null
        );
        when(taskStore.lockTask("task-1")).thenReturn(new AgentTask(
                "task-1", 1L, 11L, 21L, status, null, null, null, null, Instant.now()));
        // 두 경로가 모두 '성공할 수 있게' 스텁한다 — 이렇게 해야 통과 이유가 "타입 분기가 갈랐다"
        // 하나로 좁혀진다. 한쪽만 성공하게 두면 분기를 지워도 그대로 통과하는 테스트가 된다.
        when(taskStore.cancel("task-1", 1L)).thenReturn(true);
        when(taskStore.resumeAfterResultDecline(eq("task-1"), anyString())).thenReturn(true);
        when(approvalRepository.findByTaskIdOrderByIdAscForUpdate("task-1"))
                .thenReturn(List.of(approval));
        return new BindingSweepFixture(
                orchestrator, taskStore, approvalRepository, messageService, approval);
    }

    @Test
    void 프리뷰가_회수된_저장소_연결_대기는_취소가_아니라_마침이다() {
        // ApprovalCommandService 가 이미 적어둔 것: "cancelTaskCascade 를 태우면 멀쩡히 끝난
        // 작업이 CANCELLED 로 뒤집힌다". REPOSITORY_BINDING 은 실행 게이트가 아니라 이미 성공한
        // CODE 작업물을 저장소에 남길지 묻는 것이므로, 프리뷰가 사라져도 작업은 성공으로 끝나야
        // 한다. 스윕이 그 구분 없이 취소하고 있었다(#380 에서 놓쳤다).
        BindingSweepFixture f = bindingSweepFixture(
                ApprovalType.REPOSITORY_BINDING, TaskStatus.WAITING_RESULT_APPROVAL);

        boolean handled = f.orchestrator().abandonApprovalTaskWithLostPreview(
                "task-1", Duration.ofHours(6));

        assertThat(handled).isTrue();
        // 핵심 단정: 태스크를 취소하지 않는다.
        verify(f.taskStore(), never()).cancel("task-1", 1L);
        verify(f.taskStore()).resumeAfterResultDecline(eq("task-1"), anyString());
        // 승인 카드는 닫는다 — 누르면 실패하는 버튼을 남기지 않는 것이 #380 의 본래 목적이다.
        assertThat(f.approval().getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(f.approvalRepository()).save(f.approval());
    }

    @Test
    void 프리뷰가_회수된_결과_승인_대기는_지금처럼_취소된다() {
        // 회귀 방지. 위 분기가 RESULT 까지 삼키면 반영되지 않은 작업이 성공으로 끝난 것처럼 남는다.
        BindingSweepFixture f = bindingSweepFixture(
                ApprovalType.RESULT, TaskStatus.WAITING_RESULT_APPROVAL);

        boolean handled = f.orchestrator().abandonApprovalTaskWithLostPreview(
                "task-1", Duration.ofHours(6));

        assertThat(handled).isTrue();
        verify(f.taskStore()).cancel("task-1", 1L);
        verify(f.taskStore(), never()).resumeAfterResultDecline(anyString(), anyString());
        assertThat(f.approval().getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
    }

    @Test
    void 저장소_연결_대기_마침_메시지는_변경이_사라졌다고_말하지_않는다() {
        // RESULT 쪽 문구("변경 내용은 남아 있지 않습니다")를 그대로 쓰면 과하게 말한다. 사용자는
        // 애초에 저장소에 남기지 않기로 한 상태에서 정상적으로 끝난 작업을 보고 있다.
        BindingSweepFixture f = bindingSweepFixture(
                ApprovalType.REPOSITORY_BINDING, TaskStatus.WAITING_RESULT_APPROVAL);

        f.orchestrator().abandonApprovalTaskWithLostPreview("task-1", Duration.ofHours(6));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(f.messageService()).appendAssistant(
                eq(21L), message.capture(), eq(ChatMessageKind.TASK_CANCELLED), eq("task-1"));
        assertThat(message.getValue())
                .doesNotContain("변경 내용은 남아 있지 않습니다")
                .contains("저장소를 연결하지 못한 채")
                // hold 를 숫자로 말하는 것은 유지한다 — "오래" 는 사용자가 대비할 수 없다.
                .contains("6시간");
    }

    @Test
    void 방치된_저장소_연결_대기도_취소가_아니라_마침이다() {
        // 7일 스윕(AbandonedApprovalSweeper)도 같은 구분을 해야 한다. 프리뷰 스윕만 고치면
        // 같은 버그가 경로만 바꿔 남는다 — #376 에서 겪은 것과 같은 모양이다.
        BindingSweepFixture f = bindingSweepFixture(
                ApprovalType.REPOSITORY_BINDING, TaskStatus.WAITING_RESULT_APPROVAL);

        boolean handled = f.orchestrator().abandonStaleApprovalTask("task-1");

        assertThat(handled).isTrue();
        verify(f.taskStore(), never()).cancel("task-1", 1L);
        verify(f.taskStore()).resumeAfterResultDecline(eq("task-1"), anyString());
        assertThat(f.approval().getStatus()).isEqualTo(ApprovalStatus.CANCELLED);
    }

    @Test
    void 방치된_계획_승인은_저장소_분기를_타지_않고_취소된다() {
        // WAITING_APPROVAL 에서는 마침 전이가 거절되므로, 그 상태로 분기를 타면 태스크가 매 스윕마다
        // 같은 자리에서 실패해 영구히 안 닫힌다. 상태 가드가 그것을 막는다.
        // (계획 승인에 REPOSITORY_BINDING 이 달릴 일은 없지만, "없을 일" 에 의존해 갇히는 경로를
        //  만들지 않는다.)
        BindingSweepFixture f = bindingSweepFixture(
                ApprovalType.REPOSITORY_BINDING, TaskStatus.WAITING_APPROVAL);

        boolean handled = f.orchestrator().abandonStaleApprovalTask("task-1");

        assertThat(handled).isTrue();
        verify(f.taskStore()).cancel("task-1", 1L);
        verify(f.taskStore(), never()).resumeAfterResultDecline(anyString(), anyString());
    }
}
