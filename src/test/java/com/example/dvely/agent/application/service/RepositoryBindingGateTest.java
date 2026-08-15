package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.Project;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class RepositoryBindingGateTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final AgentMessageService agentMessageService = mock(AgentMessageService.class);

    private final RepositoryBindingGate gate = new RepositoryBindingGate(
            projectRepository, previewSessionService, taskStore, approvalRepository, agentMessageService
    );

    @Test
    void firesCompletingTheStepBeforeTheTransitionAndTheApproval() {
        AgentPlan plan = codePlan();
        stubNotBoundProject("my-project");
        stubPreviewSession();
        when(taskStore.get("task-1")).thenReturn(task("preview-url"));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        boolean fired = gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        assertThat(fired).isTrue();
        // Same §5.2 ordering as ResultApprovalGate: the task must already satisfy
        // resumeAfterResultApproval's guard before the approval becomes visible to the user,
        // otherwise an approve arriving in the gap between the two writes 409s on a RUNNING task.
        InOrder order = Mockito.inOrder(taskStore, approvalRepository);
        order.verify(taskStore).markStepCompleted("task-1", 1);
        order.verify(taskStore).markWaitingResultApproval(eq("task-1"), anyString());
        order.verify(approvalRepository).save(any(Approval.class));
    }

    @Test
    void approvalRowCarriesRepositoryBindingTypeAndTheCandidateName() {
        AgentPlan plan = codePlan();
        stubNotBoundProject("My Project");
        stubPreviewSession();
        when(taskStore.get("task-1")).thenReturn(task("preview-url"));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        ArgumentCaptor<Approval> captor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ApprovalType.REPOSITORY_BINDING);
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getProjectId()).isEqualTo(11L);
        assertThat(captor.getValue().getConversationId()).isEqualTo(21L);
        assertThat(captor.getValue().getSummary()).isEqualTo("[저장소 연결] my-project");
    }

    @Test
    void chatMessageShowsThePreviewUrlAndTheDefaultRepositoryName() {
        AgentPlan plan = codePlan();
        stubNotBoundProject("my-project");
        stubPreviewSession();
        when(taskStore.get("task-1")).thenReturn(task("https://preview.qeploy.test/session-1/"));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        gate.requestIfRequired(plan, 0, "task-1", 1L, 11L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentMessageService).appendAssistant(eq(21L), captor.capture());
        // The name shown here is the one an empty approve body falls back to, so the user must
        // see exactly what they will get if they just press approve.
        assertThat(captor.getValue())
                .contains("https://preview.qeploy.test/session-1/")
                .contains("my-project")
                .contains("501");
    }

    @Test
    void doesNotFireWhenTheProjectIsAlreadyBound() {
        // BOUND is ResultApprovalGate's territory — the two branch on the same status precisely so
        // that a single task can never open both approvals.
        AgentPlan plan = codePlan();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project("my-project", RepositoryBindingStatus.BOUND)));

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(previewSessionService, approvalRepository, agentMessageService);
        verify(taskStore, never()).markStepCompleted(anyString(), anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
    }

    @Test
    void doesNotFireWhenThereIsNoPreviewSessionToPreserve() {
        // Nothing to push means approving could only create an empty repository — an approval that
        // cannot do anything must not be opened.
        AgentPlan plan = codePlan();
        stubNotBoundProject("my-project");
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.empty());

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(approvalRepository, agentMessageService);
        verify(taskStore, never()).markStepCompleted(anyString(), anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
    }

    @Test
    void doesNotFireWhenTaskWasAlreadyCancelledBeforeGateEntry() {
        // Parking here would revive a cancelled task into a state whose only exit is a human
        // decision — the same bail-out ResultApprovalGate makes for a racing DELETE /tasks/{id}.
        AgentPlan plan = codePlan();
        stubNotBoundProject("my-project");
        stubPreviewSession();
        when(taskStore.isCancelled("task-1")).thenReturn(true);

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(approvalRepository, agentMessageService);
        verify(taskStore, never()).markStepCompleted(anyString(), anyInt());
        verify(taskStore, never()).markWaitingResultApproval(anyString(), anyString());
    }

    @Test
    void doesNotFireWhenProjectIdIsNull() {
        assertThat(gate.requestIfRequired(codePlan(), 0, "task-1", 1L, null)).isFalse();
        verifyNoInteractions(projectRepository, previewSessionService, approvalRepository);
    }

    @Test
    void doesNotFireWhenProjectCannotBeFoundAtAll() {
        AgentPlan plan = codePlan();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.empty());

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(previewSessionService, approvalRepository);
    }

    @Test
    void doesNotFireOnANonCodeStep() {
        AgentStep deploy = new AgentStep(AgentType.DEPLOY, Map.of());
        AgentPlan plan = new AgentPlan(List.of(deploy), "reason", AiProvider.OPENAI, 11L);

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(projectRepository, previewSessionService, approvalRepository);
    }

    @Test
    void doesNotFireOnAMidPlanCodeStepWhenALaterCodeStepStillExists() {
        AgentStep firstCode = new AgentStep(AgentType.CODE, Map.of("instruction", "1차 수정"));
        AgentStep deploy = new AgentStep(AgentType.DEPLOY, Map.of());
        AgentStep secondCode = new AgentStep(AgentType.CODE, Map.of("instruction", "2차 수정"));
        AgentPlan plan = new AgentPlan(List.of(firstCode, deploy, secondCode), "reason", AiProvider.OPENAI, 11L);

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isFalse();
        verifyNoInteractions(projectRepository, previewSessionService, approvalRepository);
    }

    @Test
    void firesOnlyOnTheLastOfMultipleCodeStepsInAPlan() {
        AgentStep firstCode = new AgentStep(AgentType.CODE, Map.of("instruction", "1차 수정"));
        AgentStep secondCode = new AgentStep(AgentType.CODE, Map.of("instruction", "2차 수정"));
        AgentPlan plan = new AgentPlan(List.of(firstCode, secondCode), "reason", AiProvider.OPENAI, 11L);
        stubNotBoundProject("my-project");
        stubPreviewSession();
        when(taskStore.get("task-1")).thenReturn(task("preview-url"));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        assertThat(gate.requestIfRequired(plan, 1, "task-1", 1L, 11L)).isTrue();
        verify(taskStore).markStepCompleted("task-1", 2);
    }

    @Test
    void firesWithoutAPreviewUrlWhenTheTaskHasNotRecordedOne() {
        AgentPlan plan = codePlan();
        stubNotBoundProject("my-project");
        stubPreviewSession();
        when(taskStore.get("task-1")).thenReturn(task(null));
        when(approvalRepository.save(any(Approval.class))).thenReturn(approval(501L));

        assertThat(gate.requestIfRequired(plan, 0, "task-1", 1L, 11L)).isTrue();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentMessageService).appendAssistant(eq(21L), captor.capture());
        assertThat(captor.getValue()).doesNotContain("- preview: ");
    }

    private AgentPlan codePlan() {
        AgentStep step = new AgentStep(AgentType.CODE, Map.of("instruction", "FAQ 페이지 추가"));
        return new AgentPlan(List.of(step), "reason", AiProvider.OPENAI, 11L);
    }

    private void stubNotBoundProject(String name) {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project(name, RepositoryBindingStatus.NOT_BOUND)));
    }

    private void stubPreviewSession() {
        when(previewSessionService.findByTaskId("task-1")).thenReturn(Optional.of(new PreviewSessionInfo(
                "session-1", 1L, 11L, 21L, "task-1", "container-1", 3000,
                "https://preview.qeploy.test/session-1/", LocalDateTime.now().plusMinutes(30)
        )));
    }

    private Project project(String name, RepositoryBindingStatus bindingStatus) {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L, 1L, name, ProjectStatus.ACTIVE, "vue", null, "fast", DeployStatus.DRAFT,
                null, null,
                bindingStatus == RepositoryBindingStatus.BOUND ? "octo/repo" : null,
                bindingStatus == RepositoryBindingStatus.BOUND ? "octo/repo" : null,
                RepositoryVisibility.PRIVATE, bindingStatus, RepositoryHealthStatus.UNKNOWN_ERROR,
                false, now, now
        );
    }

    private AgentTask task(String previewUrl) {
        return new AgentTask("task-1", 1L, 11L, 21L, TaskStatus.RUNNING, previewUrl, "코드 결과 요약",
                null, null, Instant.now());
    }

    private Approval approval(Long id) {
        return new Approval(id, 1L, 11L, 21L, "task-1", ApprovalType.REPOSITORY_BINDING,
                ApprovalStatus.PENDING, "[저장소 연결] my-project", LocalDateTime.now(), null);
    }
}
