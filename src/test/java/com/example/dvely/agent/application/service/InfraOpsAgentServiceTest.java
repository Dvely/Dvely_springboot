package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.docker.ContainerResourceUsage;
import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeploymentLogsResult;
import com.example.dvely.deployment.application.service.DeploymentFailureAnalysisService;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectApprovalPolicy;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.model.ProjectInfrastructureSetting;
import com.example.dvely.project.domain.repository.ProjectApprovalPolicyRepository;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectInfrastructureSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.ComputeTier;
import com.example.dvely.project.domain.value.DeploymentArchitecture;
import com.example.dvely.project.domain.value.InfrastructureConfiguration;
import com.example.dvely.project.domain.value.NetworkAccess;
import com.example.dvely.project.domain.value.StorageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class InfraOpsAgentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 11L;
    private static final String TASK_ID = "task-1";

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final DeploymentQueryService deploymentQueryService = mock(DeploymentQueryService.class);
    private final DeploymentFailureAnalysisService failureAnalysisService = mock(DeploymentFailureAnalysisService.class);
    private final PreviewSessionService previewSessionService = mock(PreviewSessionService.class);
    private final DockerContainerService dockerService = mock(DockerContainerService.class);
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository =
            mock(ProjectCloudConnectionSettingRepository.class);
    private final CloudConnectionRepository cloudConnectionRepository = mock(CloudConnectionRepository.class);
    private final ProjectInfrastructureSettingRepository infrastructureSettingRepository =
            mock(ProjectInfrastructureSettingRepository.class);
    private final ProjectApprovalPolicyRepository policyRepository = mock(ProjectApprovalPolicyRepository.class);

    private final InfraOpsAgentService service = new InfraOpsAgentService(
            projectRepository,
            deploymentQueryService,
            failureAnalysisService,
            previewSessionService,
            dockerService,
            cloudConnectionSettingRepository,
            cloudConnectionRepository,
            infrastructureSettingRepository,
            policyRepository
    );

    // ── 공통 전처리 ─────────────────────────────────────────────────────────────

    @Test
    void missingProjectIdReturnsGuidanceWithoutTouchingAnyRepository() {
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));

        var result = service.execute(step, USER_ID, TASK_ID, null);

        assertThat(result.summary()).contains("프로젝트 대화에서 요청");
        verifyNoInteractions(projectRepository, deploymentQueryService, previewSessionService, dockerService);
    }

    @Test
    void ownershipMismatchThrowsIllegalState() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));

        assertThatThrownBy(() -> service.execute(step, USER_ID, TASK_ID, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unidentifiedOperationReturnsFixedGuidance() {
        stubOwnedProject();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "DELETE_EVERYTHING"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).contains("요청을 인프라 운영 작업으로 특정하지 못했습니다");
        verifyNoInteractions(dockerService);
    }

    // ── Prompt-injection defense: LLM-supplied "resource" parameters are ignored ────────────────

    @Test
    void ignoresLlmSuppliedContainerIdAndAlwaysResolvesFromOwnedDbRow() {
        stubOwnedProject();
        stubEmptyHistories();
        stubNoCloudConnection();
        stubNoInfrastructureSetting();
        // Simulates a prompt-injection attempt: the LLM step parameters try to smuggle in a
        // foreign containerId. InfraOpsAgentService must never read it — the only session lookup
        // is previewSessionService.findActiveByProject(projectId, ownerUserId).
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of(
                "operation", "STATUS_CHECK",
                "containerId", "someone-elses-container",
                "instruction", "상태 확인해줘"
        ));

        service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(previewSessionService).findActiveByProject(PROJECT_ID, USER_ID);
        verify(dockerService, never()).getContainerStatus(any());
        verify(dockerService, never()).restartContainer(any());
    }

    // ── STATUS_CHECK (read-only, deterministic) ─────────────────────────────────

    @Test
    void statusCheckReportsAllSourcesWhenPresent() {
        stubOwnedProject();
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID)).thenReturn(List.of(
                new DeploymentHistoryResult(9L, PROJECT_ID, "GITHUB_PAGES", "v3", "https://x.qeploy.com",
                        "LIVE", LocalDateTime.now(), LocalDateTime.now(), null)
        ));
        PreviewSessionInfo session = previewSession();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getContainerStatus("container-1"))
                .thenReturn(new ContainerRuntimeStatus(true, false, null, LocalDateTime.now()));
        when(dockerService.getContainerStats("container-1"))
                .thenReturn(Optional.of(new ContainerResourceUsage(100L * 1024 * 1024, 1024L * 1024 * 1024, 12.5)));
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectCloudConnectionSetting(PROJECT_ID, 5L)));
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getProvider()).thenReturn(CloudProvider.AWS);
        when(connection.getRegion()).thenReturn("ap-northeast-2");
        when(connection.getStatus()).thenReturn(CloudConnectionStatus.CONNECTED);
        when(cloudConnectionRepository.findByIdAndOwnerUserId(5L, USER_ID)).thenReturn(Optional.of(connection));
        when(infrastructureSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(
                new ProjectInfrastructureSetting(PROJECT_ID, new InfrastructureConfiguration(
                        DeploymentArchitecture.SERVER, ComputeTier.SMALL, StorageType.NONE, NetworkAccess.PUBLIC))
        ));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary())
                .contains("LIVE")
                .contains("실행 중")
                .contains("AWS")
                .contains("아키텍처=SERVER")
                // D5: cloud provisioning honesty line is always present regardless of the other rows.
                .contains("아직 프로비저닝되지 않았습니다");
    }

    @Test
    void statusCheckReportsAbsenceForEverySourceWhenNothingExists() {
        stubOwnedProject();
        stubEmptyHistories();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        stubNoCloudConnection();
        stubNoInfrastructureSetting();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary())
                .contains("배포 이력 없음")
                .contains("실행 중인 preview 없음")
                .contains("미연결")
                .contains("미설정")
                .contains("아직 프로비저닝되지 않았습니다");
    }

    @Test
    void statusCheckDegradesOnlyTheFailingSection() {
        stubOwnedProject();
        // Deployment lookup fails (simulated GitHub/DB hiccup) — only that row should degrade.
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID))
                .thenThrow(new RuntimeException("GitHub API 일시 장애"));
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        stubNoCloudConnection();
        stubNoInfrastructureSetting();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "STATUS_CHECK"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary())
                .contains("배포: 확인 불가")
                .contains("실행 중인 preview 없음")
                .contains("미연결");
    }

    // ── LOG_VIEW ─────────────────────────────────────────────────────────────

    @Test
    void logViewReturnsNoLogsMessageWhenNoSourceExists() {
        stubOwnedProject();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        stubEmptyHistories();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "LOG_VIEW"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).isEqualTo("조회할 로그가 없습니다(실행 중 preview·배포 이력 없음).");
    }

    @Test
    void logViewTruncatesOversizedLogToConfiguredCharLimit() {
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        String hugeLog = "x".repeat(5_000);
        when(dockerService.getContainerLogs(eq("container-1"), eq(50), isNull())).thenReturn(hugeLog);
        stubEmptyHistories();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "LOG_VIEW"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).contains("[Preview 로그]").contains("(생략)");
        assertThat(result.summary().length()).isLessThan(hugeLog.length());
    }

    @Test
    void logViewDegradesDeploymentSectionOnHistoryFailureWithoutDiscardingPreviewLog() {
        // Symmetric with statusCheckDegradesOnlyTheFailingSection (review Medium fix,
        // x-cloudops-review.md): a GitHub Actions hiccup on the deployment-history lookup must
        // degrade only the [배포 로그] row, not discard the already-computed [Preview 로그] section
        // by escalating the whole task to FAILED.
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getContainerLogs(eq("container-1"), eq(50), isNull())).thenReturn("preview log line");
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID))
                .thenThrow(new RuntimeException("GitHub API 일시 장애"));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "LOG_VIEW"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary())
                .contains("[Preview 로그]").contains("preview log line")
                .contains("[배포 로그]").contains("확인 불가");
        verify(deploymentQueryService, never()).getDeploymentLogs(anyLong(), anyLong());
    }

    // ── FAILURE_ANALYSIS ─────────────────────────────────────────────────────

    @Test
    void failureAnalysisInvokesU6ServiceWhenLatestDeploymentFailed() {
        stubOwnedProject();
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID)).thenReturn(List.of(
                new DeploymentHistoryResult(9L, PROJECT_ID, "GITHUB_PAGES", "v3", null,
                        "FAILED", LocalDateTime.now(), LocalDateTime.now(), null)
        ));
        when(failureAnalysisService.analyze(USER_ID, 9L)).thenReturn(new DeploymentFailureAnalysisResult(
                9L, "빌드 스크립트 오류입니다.", "log excerpt", "package.json을 확인하세요.", "LLM", LocalDateTime.now()
        ));
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "FAILURE_ANALYSIS"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(failureAnalysisService).analyze(USER_ID, 9L);
        assertThat(result.summary()).contains("빌드 스크립트 오류입니다.").contains("package.json을 확인하세요.");
    }

    @Test
    void failureAnalysisSkipsU6ServiceWhenLatestDeploymentDidNotFail() {
        stubOwnedProject();
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID)).thenReturn(List.of(
                new DeploymentHistoryResult(9L, PROJECT_ID, "GITHUB_PAGES", "v3", "https://x.qeploy.com",
                        "LIVE", LocalDateTime.now(), LocalDateTime.now(), null)
        ));
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "FAILURE_ANALYSIS"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(failureAnalysisService, never()).analyze(anyLong(), anyLong());
        assertThat(result.summary()).contains("최근 실패한 배포가 없습니다");
    }

    @Test
    void failureAnalysisSkipsU6ServiceWhenNoHistoryExists() {
        stubOwnedProject();
        stubEmptyHistories();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "FAILURE_ANALYSIS"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(failureAnalysisService, never()).analyze(anyLong(), anyLong());
        assertThat(result.summary()).contains("최근 실패한 배포가 없습니다").contains("배포 이력 없음");
    }

    // ── RESTART (the only mutating op — approval-gated by AgentOrchestrator upstream) ──────────

    @Test
    void restartsActivePreviewSessionAndReportsUrl() {
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        PreviewSessionInfo refreshed = previewSessionWithPort(40001);
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getMappedPort("container-1")).thenReturn(40001);
        when(previewSessionService.updateHostPort("session-1", 40001)).thenReturn(refreshed);
        when(dockerService.getContainerStatus("container-1"))
                .thenReturn(new ContainerRuntimeStatus(true, false, null, LocalDateTime.now()));
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, true)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(dockerService).restartContainer("container-1");
        assertThat(result.summary()).contains("재시작했습니다").contains(refreshed.publicUrl());
        assertThat(result.summary()).doesNotContain("승인 정책이 꺼져 있어");
    }

    // Issue #71 (High): RESTART must re-query the container's newly (re)assigned ephemeral host
    // port and persist it onto the preview session row — otherwise PreviewGatewayService keeps
    // proxying to the pre-restart port and every subsequent gateway hit 502s even though this
    // response claims "정상 실행 중". This is the actual regression guard for the fix, distinct from
    // the URL-content assertion above: it pins down call order (restart -> re-query port ->
    // persist) and the exact new port value reaching PreviewSessionService.
    @Test
    void restartRebindsPreviewSessionToNewlyAssignedHostPortAfterRestart() {
        stubOwnedProject();
        PreviewSessionInfo session = previewSession(); // pre-restart hostPort=3000 (stale)
        PreviewSessionInfo refreshed = previewSessionWithPort(40001);
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getMappedPort("container-1")).thenReturn(40001);
        when(previewSessionService.updateHostPort("session-1", 40001)).thenReturn(refreshed);
        when(dockerService.getContainerStatus("container-1"))
                .thenReturn(new ContainerRuntimeStatus(true, false, null, LocalDateTime.now()));
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, true)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        InOrder order = inOrder(dockerService, previewSessionService);
        order.verify(dockerService).restartContainer("container-1");
        order.verify(dockerService).getMappedPort("container-1");
        order.verify(previewSessionService).updateHostPort("session-1", 40001);
        ArgumentCaptor<Integer> portCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(previewSessionService).updateHostPort(eq("session-1"), portCaptor.capture());
        assertThat(portCaptor.getValue()).isEqualTo(40001).isNotEqualTo(session.hostPort());
    }

    // A port-rebind failure right after a successful restartContainer() means the restart is not
    // actually usable yet (we don't know the real reachable port) — unlike the cosmetic
    // getContainerStatus re-check below, this must propagate to task FAILED rather than silently
    // report "정상 실행 중" against a port we couldn't confirm (that would just reproduce issue #71
    // under a different trigger).
    @Test
    void restartPropagatesFailureWhenPortRebindFailsAfterSuccessfulRestart() {
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getMappedPort("container-1"))
                .thenThrow(new IllegalStateException("컨테이너 포트 바인딩이 없습니다. containerId=container-1"));
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, true)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        assertThatThrownBy(() -> service.execute(step, USER_ID, TASK_ID, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("포트 바인딩");

        verify(dockerService).restartContainer("container-1");
        verify(previewSessionService, never()).updateHostPort(any(), anyInt());
        verify(dockerService, never()).getContainerStatus(any());
    }

    @Test
    void restartDegradesPostRestartStatusCheckWithoutFailingTheAlreadyCompletedRestart() {
        // Review Medium fix (x-cloudops-review.md): restartContainer() already succeeded (and
        // mutated real state) before the post-restart getContainerStatus re-check runs — a Docker
        // hiccup on that re-check alone must not turn a completed restart into a task FAILED.
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        PreviewSessionInfo refreshed = previewSessionWithPort(40001);
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getMappedPort("container-1")).thenReturn(40001);
        when(previewSessionService.updateHostPort("session-1", 40001)).thenReturn(refreshed);
        when(dockerService.getContainerStatus("container-1"))
                .thenThrow(new RuntimeException("Docker 데몬 일시 응답 없음"));
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, true)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(dockerService).restartContainer("container-1");
        assertThat(result.summary())
                .contains("재시작했습니다")
                .contains(refreshed.publicUrl())
                .contains("확인 불가(재시작 자체는 완료됨)");
    }

    @Test
    void restartWithNoActiveSessionReportsGuidanceWithoutTouchingDocker() {
        stubOwnedProject();
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, true)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        verify(dockerService, never()).restartContainer(any());
        assertThat(result.summary()).contains("재시작할 실행 중인 서버가 없습니다");
    }

    @Test
    void restartWarnsWhenApprovalPolicyIsOff() {
        stubOwnedProject();
        PreviewSessionInfo session = previewSession();
        PreviewSessionInfo refreshed = previewSessionWithPort(40001);
        when(previewSessionService.findActiveByProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(session));
        when(dockerService.getMappedPort("container-1")).thenReturn(40001);
        when(previewSessionService.updateHostPort("session-1", 40001)).thenReturn(refreshed);
        when(dockerService.getContainerStatus("container-1"))
                .thenReturn(new ContainerRuntimeStatus(true, false, null, LocalDateTime.now()));
        // Policy explicitly OFF for INFRA_OPERATION — AgentOrchestrator would have skipped
        // creating an approval, so this op runs immediately and must carry the warning (design D4).
        when(policyRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new ProjectApprovalPolicy(PROJECT_ID, true, true, true, false)));
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESTART"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).startsWith("[서비스 영향]").contains("승인 정책이 꺼져 있어 즉시 실행했습니다");
    }

    // ── Unsupported ops (BI-176/177 detection surfaced as guidance, never approval/execution) ──

    @Test
    void resourceScalingIsRejectedWithGuidanceAndTouchesNothingMutating() {
        stubOwnedProject();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESOURCE_SCALING"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).startsWith("[서비스 영향] [비용 증가 가능]").contains("Cloud Infrastructure");
        verifyNoInteractions(dockerService, failureAnalysisService, previewSessionService);
    }

    @Test
    void autoscalingChangeIsRejectedWithGuidance() {
        stubOwnedProject();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "AUTOSCALING_CHANGE"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).startsWith("[비용 증가 가능]").contains("오토스케일링");
        verifyNoInteractions(dockerService);
    }

    @Test
    void resourceCleanupIsRejectedWithGuidance() {
        stubOwnedProject();
        AgentStep step = new AgentStep(AgentType.INFRA_OPERATE, Map.of("operation", "RESOURCE_CLEANUP"));

        var result = service.execute(step, USER_ID, TASK_ID, PROJECT_ID);

        assertThat(result.summary()).startsWith("[서비스 영향]").contains("리소스 정리");
        verifyNoInteractions(dockerService);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubOwnedProject() {
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(mock(Project.class)));
    }

    private void stubEmptyHistories() {
        when(deploymentQueryService.getDeploymentHistories(USER_ID, PROJECT_ID)).thenReturn(List.of());
    }

    private void stubNoCloudConnection() {
        when(cloudConnectionSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
    }

    private void stubNoInfrastructureSetting() {
        when(infrastructureSettingRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
    }

    private PreviewSessionInfo previewSession() {
        return new PreviewSessionInfo(
                "session-1", USER_ID, PROJECT_ID, 21L, TASK_ID,
                "container-1", 3000, "https://preview.qeploy.com/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
    }

    // Same session identity/publicUrl as previewSession() but with the hostPort a post-restart
    // getMappedPort()+updateHostPort() round trip would produce — publicUrl is unchanged (it
    // never encodes the port; see PreviewSessionService#acquire) precisely because only hostPort
    // is meant to change across a restart.
    private PreviewSessionInfo previewSessionWithPort(int hostPort) {
        return new PreviewSessionInfo(
                "session-1", USER_ID, PROJECT_ID, 21L, TASK_ID,
                "container-1", hostPort, "https://preview.qeploy.com/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
    }
}
