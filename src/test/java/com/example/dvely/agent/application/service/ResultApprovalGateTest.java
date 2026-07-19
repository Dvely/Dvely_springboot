package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ResultApprovalGateTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);
    private final PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
    private final PreviewBranchPushService previewBranchPushService = mock(PreviewBranchPushService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final AgentMessageService agentMessageService = mock(AgentMessageService.class);

    private final ResultApprovalGate gate = new ResultApprovalGate(
            projectRepository, policyRepository, previewSessionService, previewBranchPushService,
            userRepository, authCommandService, taskStore, approvalRepository, agentMessageService
    );

    @Test
    void firesAndPushesCompletesStepThenTransitionsThenCreatesApprovalInOrder() {
        AgentPlan plan = codePlan();
        stubBoundPolicyOnProject();
        stubPreviewSessionAndUser();
        when(taskStore.get("task-1")).thenReturn(task(TaskStatus.RUNNING, "preview-url", "코드 결과 요약"));
        Approval saved = approval(501L);
        when(approvalRepository.save(any(Approval.class))).thenReturn(saved);

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        assertThat(fired).isTrue();
        verify(previewBranchPushService).push("container-1", "gh-token", "octo", "octo/repo", false, "task-1");
        InOrder order = Mockito.inOrder(previewBranchPushService, taskStore, approvalRepository);
        order.verify(previewBranchPushService).push(anyString(), anyString(), anyString(), anyString(), eq(false), anyString());
        order.verify(taskStore).markStepCompleted("task-1", 1);
        order.verify(taskStore).markWaitingResultApproval(eq("task-1"), anyString());
        order.verify(approvalRepository).save(any(Approval.class));
        verify(agentMessageService).appendAssistant(eq(21L), anyString());
    }

    @Test
    void approvalRowCarriesResultTypeAndTaskSummary() {
        AgentPlan plan = codePlan();
        stubBoundPolicyOnProject();
        stubPreviewSessionAndUser();
        when(taskStore.get("task-1")).thenReturn(task(TaskStatus.RUNNING, "preview-url", "FAQ 페이지 추가"));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval source = invocation.getArgument(0);
            return new Approval(501L, source.getOwnerUserId(), source.getProjectId(), source.getConversationId(),
                    source.getTaskId(), source.getType(), ApprovalStatus.PENDING, source.getSummary(),
                    LocalDateTime.now(), null);
        });

        gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        org.mockito.ArgumentCaptor<Approval> captor = org.mockito.ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ApprovalType.RESULT);
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getSummary()).isEqualTo("[결과 반영] FAQ 페이지 추가");
    }

    @Test
    void doesNotFireWhenTaskWasAlreadyCancelledBeforeGateEntry() {
        // BLOCKING-2 regression: models a DELETE /tasks/{id} landing during the CODE step / the
        // gap before this method starts (AgentPlanExecutor's own isCancelled check happens before
        // this method is even called) — the gate must not push, must not revive the cancelled
        // task into WAITING_RESULT_APPROVAL, and must not create an orphaned RESULT approval for
        // it. Bailing out here should also mean zero GitHub work — not just zero DB writes.
        AgentPlan plan = codePlan();
        stubBoundPolicyOnProject();
        when(taskStore.isCancelled("task-1")).thenReturn(true);

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        assertThat(fired).isFalse();
        verifyNoInteractions(previewSessionService, previewBranchPushService, approvalRepository);
        verify(taskStore, never()).markStepCompleted(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
    }

    @Test
    void doesNotFireWhenPolicyIsOff() {
        AgentPlan plan = codePlan();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(policyRepository.findByProjectId(11L))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(11L, true, true, true, true, false)));

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        assertThat(fired).isFalse();
        verifyNoInteractions(previewBranchPushService, previewSessionService, approvalRepository);
        verify(taskStore, never()).markStepCompleted(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
    }

    @Test
    void doesNotFireWhenProjectIdIsNull() {
        AgentPlan plan = codePlan();

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, null);

        assertThat(fired).isFalse();
        verifyNoInteractions(projectRepository, policyRepository, previewBranchPushService, approvalRepository);
    }

    @Test
    void doesNotFireWhenProjectIsNotBound() {
        AgentPlan plan = codePlan();
        Project notBound = new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.DRAFT,
                null, null, null, null, RepositoryVisibility.PRIVATE, RepositoryBindingStatus.NOT_BOUND,
                RepositoryHealthStatus.UNKNOWN_ERROR, false, LocalDateTime.now(), LocalDateTime.now()
        );
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(notBound));

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        assertThat(fired).isFalse();
        verifyNoInteractions(previewBranchPushService, approvalRepository);
    }

    @Test
    void doesNotFireWhenProjectCannotBeFoundAtAll() {
        AgentPlan plan = codePlan();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(previewBranchPushService, approvalRepository);
    }

    @Test
    void doesNotFireOnAMidPlanCodeStepWhenALaterCodeStepStillExists() {
        AgentStep firstCode = new AgentStep(AgentType.CODE, Map.of("instruction", "1차 수정"));
        AgentStep deploy = new AgentStep(AgentType.DEPLOY, Map.of());
        AgentStep secondCode = new AgentStep(AgentType.CODE, Map.of("instruction", "2차 수정"));
        AgentPlan plan = new AgentPlan(List.of(firstCode, deploy, secondCode), "reason", AiProvider.OPENAI, 11L);

        // index 0 is a CODE step, but not the LAST one in the plan — must not fire, and must not
        // even reach the D9 project/policy lookups (isLastCodeStep is checked first).
        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(projectRepository, policyRepository, previewBranchPushService, approvalRepository);
    }

    @Test
    void firesOnlyOnTheLastOfMultipleCodeStepsInAPlan() {
        AgentStep firstCode = new AgentStep(AgentType.CODE, Map.of("instruction", "1차 수정"));
        AgentStep secondCode = new AgentStep(AgentType.CODE, Map.of("instruction", "2차 수정"));
        AgentPlan plan = new AgentPlan(List.of(firstCode, secondCode), "reason", AiProvider.OPENAI, 11L);
        stubBoundPolicyOnProject();
        stubPreviewSessionAndUser();
        when(taskStore.get("task-1")).thenReturn(task(TaskStatus.RUNNING, "preview-url", "2차 수정 완료"));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        assertThat(gate.requestIfRequired(plan, 1, "task-1", 1L, 11L)).isTrue();
        verify(taskStore).markStepCompleted("task-1", 2);
    }

    @Test
    void pushFailurePropagatesWithoutCompletingStepOrCreatingApproval() {
        AgentPlan plan = codePlan();
        stubBoundPolicyOnProject();
        stubPreviewSessionAndUser();
        Mockito.doThrow(new IllegalStateException("네트워크 오류"))
                .when(previewBranchPushService)
                .push(anyString(), anyString(), anyString(), anyString(), eq(false), anyString());

        assertThatThrownBy(() -> gate.requestIfRequired(plan, 0, "task-1", 1L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("네트워크 오류");

        // §5.2: completeStep only after push succeeds — a push failure must leave currentStep
        // untouched so /retry re-runs the CODE step instead of silently skipping past it.
        verify(taskStore, never()).markStepCompleted(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
        verifyNoInteractions(approvalRepository);
    }

    @Test
    void missingPreviewSessionFailsFastWithoutTouchingTaskState() {
        AgentPlan plan = codePlan();
        stubBoundPolicyOnProject();
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gate.requestIfRequired(plan, 0, "task-1", 1L, 11L))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(previewBranchPushService, approvalRepository);
        verify(taskStore, never()).markStepCompleted(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private AgentPlan codePlan() {
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ 페이지 추가"));
        return new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
    }

    private void stubBoundPolicyOnProject() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(boundProject()));
        when(policyRepository.findByProjectId(11L)).thenReturn(Optional.empty()); // fail-safe ON
    }

    private void stubPreviewSessionAndUser() {
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(new PreviewSessionInfo(
                "session-1", 1L, 11L, 21L, "task-1", "container-1", 3000,
                "https://preview.qeploy.test/session-1/", LocalDateTime.now().plusMinutes(30)
        )));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
    }

    private Project boundProject() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.LIVE,
                null, "v3", "octo/repo", "octo/repo", RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY, false, now, now
        );
    }

    private User activeUser() {
        return new User(1L, new GithubId("123"), "octo", null, 100L, "gh-token", "refresh-token",
                LocalDateTime.now().plusHours(1));
    }

    private AgentTask task(TaskStatus status, String previewUrl, String summary) {
        return new AgentTask("task-1", 1L, 11L, 21L, status, previewUrl, summary, null, null, Instant.now());
    }

    private Approval approval(Long id) {
        return new Approval(id, 1L, 11L, 21L, "task-1", ApprovalType.RESULT, ApprovalStatus.PENDING,
                "[결과 반영] 요약", LocalDateTime.now(), null);
    }
}
