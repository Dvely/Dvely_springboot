package com.example.dvely.project.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dvely.approval.application.query.ApprovalQueryService;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.change.application.result.ChangeResult;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.application.result.ProjectInfrastructureSettingsResult;
import com.example.dvely.project.application.service.ProjectInfrastructureSettingsService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectQueryServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GithubRepositoryPort githubRepositoryPort;

    @Mock
    private DeploymentQueryService deploymentQueryService;

    @Mock
    private ChangeService changeService;

    @Mock
    private ApprovalQueryService approvalQueryService;

    @Mock
    private DomainBindingQueryService domainBindingQueryService;

    @Mock
    private ProjectInfrastructureSettingsService infrastructureSettingsService;

    private ProjectQueryService service;
    private LocalDateTime now;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectQueryService(
                projectRepository,
                githubRepositoryPort,
                deploymentQueryService,
                changeService,
                approvalQueryService,
                domainBindingQueryService,
                infrastructureSettingsService
        );
        now = LocalDateTime.now();
        project = project();
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
    }

    @Test
    void overviewUsesActualEventsAndPrioritizesConnectedCustomDomain() {
        project.synchronizeRepositoryVersion("v8", now.minusMinutes(1));
        when(githubRepositoryPort.getRecentCommits(1L, "octo/repo", 20))
                .thenReturn(List.of(new GithubRepositoryPort.GithubCommit(
                        "abc123",
                        "feat: release",
                        "octo",
                        OffsetDateTime.now().minusHours(2)
                )));
        when(githubRepositoryPort.checkRepositoryHealth(1L, "octo/repo"))
                .thenReturn(RepositoryHealthStatus.HEALTHY);
        when(deploymentQueryService.getDeploymentHistories(1L, 11L))
                .thenReturn(List.of(
                        deployment(101L, "v7", null, "FAILED", now.minusMinutes(5)),
                        deployment(100L, "v6", "https://octo.github.io/repo", "LIVE", now.minusDays(1))
                ));
        when(changeService.getProjectChanges(1L, 11L))
                .thenReturn(List.of(new ChangeResult(
                        201L,
                        11L,
                        21L,
                        "task-1",
                        "preview-1",
                        "PREVIEW_READY",
                        "결제 화면 수정",
                        null,
                        null,
                        null,
                        null,
                        now.minusMinutes(4),
                        now.minusMinutes(2)
                )));
        when(approvalQueryService.getProjectApprovals(1L, 11L))
                .thenReturn(List.of(new ApprovalResult(
                        301L,
                        11L,
                        21L,
                        "task-1",
                        "DEPLOYMENT",
                        "APPROVED",
                        "운영 배포",
                        now.minusMinutes(4),
                        now.minusMinutes(3)
                )));
        when(domainBindingQueryService.getProjectDomains(1L, 11L))
                .thenReturn(List.of(
                        domain(402L, DomainType.MANAGED_SUBDOMAIN, "sample.qeploy.com", now.minusMinutes(1)),
                        domain(401L, DomainType.CUSTOM_DOMAIN, "www.example.com", now.minusMinutes(6))
                ));
        when(infrastructureSettingsService.get(1L, 11L))
                .thenReturn(new ProjectInfrastructureSettingsResult(
                        11L,
                        501L,
                        "AWS",
                        "production",
                        "ap-northeast-2",
                        "CONNECTED",
                        now.minusMinutes(10),
                        now.minusMinutes(10)
                ));

        var overview = service.getOverview(1L, 11L);

        assertThat(overview.currentUrl()).isEqualTo("https://www.example.com");
        assertThat(overview.deployStatus()).isEqualTo("FAILED");
        assertThat(overview.currentVersion()).isEqualTo("v6");
        assertThat(overview.repositoryVersion()).isEqualTo("v8");
        assertThat(overview.domainSummary().hostname()).isEqualTo("www.example.com");
        assertThat(overview.domainSummary().hostingTarget()).isEqualTo("GITHUB_PAGES");
        assertThat(overview.domainSummary().certificateStatus()).isEqualTo("ACTIVE");
        assertThat(overview.domainSummary().httpsEnforced()).isTrue();
        assertThat(overview.cloudSummary().status()).isEqualTo("CONNECTED");
        assertThat(overview.recentChanges())
                .extracting(event -> event.type())
                .containsExactly("DOMAIN_CONNECTED", "CHANGE_PREVIEW_READY", "APPROVAL_APPROVED");
        assertThat(overview.operationActions())
                .filteredOn(action -> action.type().equals("DEPLOY"))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isTrue();
                    assertThat(action.reason()).contains("재시도");
                });
    }

    @Test
    void commitsFallBackToWebhookSnapshotWhenGithubIsUnavailable() {
        project.synchronizeRepositoryHead(
                "webhook-sha",
                "fix: synchronized",
                "octo",
                now.minusMinutes(2),
                now.minusMinutes(1)
        );
        when(githubRepositoryPort.getRecentCommits(1L, "octo/repo", 20))
                .thenThrow(new IllegalStateException("GitHub unavailable"));

        var commits = service.getCommits(1L, 11L);

        assertThat(commits).singleElement().satisfies(commit -> {
            assertThat(commit.sha()).isEqualTo("webhook-sha");
            assertThat(commit.message()).isEqualTo("fix: synchronized");
            assertThat(commit.author()).isEqualTo("octo");
        });
    }

    @Test
    void activityLogsContainPersistedDomainEventsInsteadOfCurrentValuePlaceholders() {
        when(deploymentQueryService.getDeploymentHistories(1L, 11L)).thenReturn(List.of());
        when(changeService.getProjectChanges(1L, 11L)).thenReturn(List.of());
        when(approvalQueryService.getProjectApprovals(1L, 11L)).thenReturn(List.of());
        when(domainBindingQueryService.getProjectDomains(1L, 11L))
                .thenReturn(List.of(domain(
                        401L,
                        DomainType.MANAGED_SUBDOMAIN,
                        "sample.qeploy.com",
                        now.minusMinutes(1)
                )));

        var logs = service.getActivityLogs(1L, 11L);

        assertThat(logs)
                .extracting(event -> event.type())
                .containsExactly("DOMAIN_CONNECTED", "PROJECT_CREATED");
        assertThat(logs.get(0).message()).contains("sample.qeploy.com", "CONNECTED");
    }

    private Project project() {
        return new Project(
                11L,
                1L,
                "sample",
                ProjectStatus.ACTIVE,
                "blank",
                null,
                "fast",
                DeployStatus.LIVE,
                "https://octo.github.io/repo",
                "v6",
                "octo/repo",
                "octo/repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                false,
                now.minusDays(30),
                now.minusMinutes(1)
        );
    }

    private DeploymentHistoryResult deployment(Long id,
                                                String version,
                                                String url,
                                                String status,
                                                LocalDateTime timestamp) {
        return new DeploymentHistoryResult(
                id,
                11L,
                "LATEST",
                version,
                url,
                status,
                timestamp,
                timestamp,
                null
        );
    }

    private DomainBindingResult domain(Long id,
                                       DomainType type,
                                       String hostname,
                                       LocalDateTime timestamp) {
        return new DomainBindingResult(
                id,
                11L,
                type,
                DomainHostingTarget.GITHUB_PAGES,
                hostname,
                DomainStatus.CONNECTED,
                VerificationMethod.CNAME,
                "octo.github.io",
                true,
                CertificateStatus.ACTIVE,
                java.time.LocalDate.now().plusMonths(2),
                timestamp,
                timestamp.minusMinutes(1),
                timestamp
        );
    }
}
