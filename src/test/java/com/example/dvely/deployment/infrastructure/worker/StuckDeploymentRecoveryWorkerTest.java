package com.example.dvely.deployment.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.service.DeploymentOutcomeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import com.example.dvely.deployment.infrastructure.config.StuckDeploymentRecoveryProperties;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StuckDeploymentRecoveryWorkerTest {

    private DeploymentHistoryRepository historyRepository;
    private ProjectRepository projectRepository;
    private UserRepository userRepository;
    private AuthCommandService authCommandService;
    private GithubActionsPort githubActionsPort;
    private DeploymentOutcomeService outcomeService;
    private StuckDeploymentRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        historyRepository = mock(DeploymentHistoryRepository.class);
        projectRepository = mock(ProjectRepository.class);
        userRepository = mock(UserRepository.class);
        authCommandService = mock(AuthCommandService.class);
        githubActionsPort = mock(GithubActionsPort.class);
        outcomeService = mock(DeploymentOutcomeService.class);
        worker = new StuckDeploymentRecoveryWorker(
                historyRepository,
                projectRepository,
                userRepository,
                authCommandService,
                githubActionsPort,
                outcomeService,
                new StuckDeploymentRecoveryProperties(60000L, 20, 10, 120)
        );
    }

    @Test
    void aDeploymentGithubAlreadySucceededIsClosedAsSuccessNotFailure() {
        // 실제 사고가 이것이었다. GitHub 은 전부 성공하고 사이트도 떴는데 우리 이력만 멈춰
        // 있었다. 시간이 지났다고 FAILED 로 닫으면 멀쩡히 배포된 것을 실패했다고 알리게 된다.
        DeploymentHistory history = stuckHistory(901L, LocalDateTime.now().minusMinutes(20));
        Project project = project();
        givenStuck(history);
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        givenActiveToken();
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 901L))
                .thenReturn(new GithubActionsPort.WorkflowRunStatus(901L, "completed", "success"));

        worker.recoverStuckDeployments();

        verify(outcomeService).applySuccess(history, project);
        verify(outcomeService, never()).applyFailure(any(), any(), anyString());
    }

    @Test
    void aDeploymentGithubFailedIsClosedWithThatConclusion() {
        DeploymentHistory history = stuckHistory(901L, LocalDateTime.now().minusMinutes(20));
        Project project = project();
        givenStuck(history);
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        givenActiveToken();
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 901L))
                .thenReturn(new GithubActionsPort.WorkflowRunStatus(901L, "completed", "failure"));

        worker.recoverStuckDeployments();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outcomeService).applyFailure(eq(history), eq(project), reason.capture());
        org.assertj.core.api.Assertions.assertThat(reason.getValue()).contains("failure");
        verify(outcomeService, never()).applySuccess(any(), any());
    }

    @Test
    void aRunStillInProgressIsLeftAlone() {
        // 배포가 오래 걸리는 것은 정상이다. 아직 도는 중인 것을 닫으면 안 된다.
        DeploymentHistory history = stuckHistory(901L, LocalDateTime.now().minusMinutes(20));
        givenStuck(history);
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project()));
        givenActiveToken();
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 901L))
                .thenReturn(new GithubActionsPort.WorkflowRunStatus(901L, "in_progress", null));

        worker.recoverStuckDeployments();

        verifyNoInteractions(outcomeService);
    }

    @Test
    void aRunStillUnfinishedPastTheAbandonWindowIsClosedAsUnknownOutcome() {
        // 여기서 닫힌 배포는 실제로는 성공했을 수도 있다. 그래서 사유가 "실패"가 아니라
        // "결과를 확인할 수 없습니다"여야 한다.
        DeploymentHistory history = stuckHistory(901L, LocalDateTime.now().minusMinutes(121));
        Project project = project();
        givenStuck(history);
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        givenActiveToken();
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 901L))
                .thenReturn(new GithubActionsPort.WorkflowRunStatus(901L, "in_progress", null));

        worker.recoverStuckDeployments();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outcomeService).applyFailure(eq(history), eq(project), reason.capture());
        org.assertj.core.api.Assertions.assertThat(reason.getValue()).contains("확인할 수 없습니다");
    }

    @Test
    void aHistoryWithoutARunIdIsLookedUpByCorrelationId() {
        // 디스패치 때 실행을 못 찾으면 runId 가 빈 채로 IN_PROGRESS 가 된다.
        DeploymentHistory history = stuckHistory(null, LocalDateTime.now().minusMinutes(20));
        Project project = project();
        givenStuck(history);
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        givenActiveToken();
        when(githubActionsPort.findWorkflowRun(
                eq("user-token"), eq("octo/repo"), anyString(), eq("correlation-51"), any(), any()))
                .thenReturn(new GithubActionsPort.WorkflowRunMatch(
                        902L, "workflow-sha", "completed", "success"));

        worker.recoverStuckDeployments();

        verify(outcomeService).applySuccess(history, project);
    }

    @Test
    void oneHistoryFailingDoesNotAbortTheRestOfTheBatch() {
        // GitHub 호출은 레이트 리밋·토큰 만료로 언제든 실패한다. 다음 주기에 다시 물으면 된다.
        DeploymentHistory failing = stuckHistory(901L, LocalDateTime.now().minusMinutes(20));
        DeploymentHistory healthy = stuckHistory(902L, LocalDateTime.now().minusMinutes(20));
        Project project = project();
        when(historyRepository.findDispatchedAwaitingOutcome(any(), eq(20)))
                .thenReturn(List.of(failing, healthy));
        when(projectRepository.findById(11L)).thenReturn(Optional.of(project));
        givenActiveToken();
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 901L))
                .thenThrow(new IllegalStateException("rate limited"));
        when(githubActionsPort.getWorkflowRunStatus("user-token", "octo/repo", 902L))
                .thenReturn(new GithubActionsPort.WorkflowRunStatus(902L, "completed", "success"));

        worker.recoverStuckDeployments();

        verify(outcomeService).applySuccess(healthy, project);
    }

    private void givenStuck(DeploymentHistory history) {
        when(historyRepository.findDispatchedAwaitingOutcome(any(), eq(20)))
                .thenReturn(List.of(history));
    }

    private void givenActiveToken() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User(
                1L,
                new GithubId("123"),
                "octo",
                null,
                100L,
                "user-token",
                "refresh-token",
                LocalDateTime.now().plusHours(1)
        )));
    }

    private DeploymentHistory stuckHistory(Long runId, LocalDateTime updatedAt) {
        return new DeploymentHistory(
                runId == null ? 51L : runId - 850L,
                1L,
                11L,
                DeployTargetType.LATEST,
                "v7",
                "https://octo.github.io/repo/",
                DeployStatus.IN_PROGRESS,
                runId,
                "correlation-51",
                "release-sha",
                "workflow-sha",
                "Release title",
                "Release description",
                "octo",
                "https://avatars.example/octo",
                17,
                updatedAt.minusMinutes(5),
                "task-51",
                null,
                1,
                3,
                null,
                null,
                null,
                updatedAt.minusMinutes(1),
                updatedAt,
                null
        );
    }

    private Project project() {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "blank",
                "vue",
                "fast",
                DeployStatus.IN_PROGRESS,
                "https://octo.github.io/repo/",
                "v7",
                "octo/repo",
                "octo/repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                false,
                now,
                now
        );
    }
}
