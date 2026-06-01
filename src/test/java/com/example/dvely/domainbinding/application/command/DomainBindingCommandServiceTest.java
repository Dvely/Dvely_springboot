package com.example.dvely.domainbinding.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.application.port.out.DnsLookupPort;
import com.example.dvely.domainbinding.application.port.out.HostingCustomDomainPort;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainBindingCommandServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DeploymentHistoryRepository deploymentHistoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthCommandService authCommandService;

    @Mock
    private DomainBindingRepository domainBindingRepository;

    @Mock
    private CloudflareDnsPort cloudflareDnsPort;

    @Mock
    private DnsLookupPort dnsLookupPort;

    @Mock
    private HostingCustomDomainPort hostingCustomDomainPort;

    @Mock
    private GithubActionsPort githubActionsPort;

    @Mock
    private GithubRepoPort githubRepoPort;

    private DomainBindingCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = commandService(new CloudflareProperties(
                null,
                null,
                "qeploy.com",
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void bindManagedSubdomain_usesGithubPagesHostEvenWhenCurrentUrlIsManagedDomain() {
        Project project = boundProject("https://my-project.qeploy.com/");
        User user = activeUser();

        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(domainBindingRepository.existsByHostnameIgnoreCase("my-project.qeploy.com")).thenReturn(false);
        when(cloudflareDnsPort.createCnameRecord("my-project.qeploy.com", "octo.github.io"))
                .thenReturn("cf-record-1");
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo")).thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");
        when(domainBindingRepository.save(any(DomainBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DomainBindingResult result = commandService.bindDomain(
                1L,
                11L,
                new BindDomainCommand(DomainType.MANAGED_SUBDOMAIN, "my-project", null, null)
        );

        assertThat(result.dnsTarget()).isEqualTo("octo.github.io");
        assertThat(result.status()).isEqualTo(DomainStatus.VERIFYING);
        verify(cloudflareDnsPort).createCnameRecord("my-project.qeploy.com", "octo.github.io");
        verify(hostingCustomDomainPort).setCustomDomain("user-token", "octo/repo", "my-project.qeploy.com");
        verify(githubActionsPort).createOrUpdateWorkflow(
                org.mockito.ArgumentMatchers.eq("user-token"),
                org.mockito.ArgumentMatchers.eq("octo/repo"),
                org.mockito.ArgumentMatchers.eq(DeployWorkflowTemplate.fileName()),
                contains("https://api.github.com/repos/${GITHUB_REPOSITORY}/pages")
        );
        verify(githubActionsPort).triggerWorkflow(
                "user-token",
                "octo/repo",
                DeployWorkflowTemplate.fileName(),
                "main",
                "v1.0.0"
        );
        verify(deploymentHistoryRepository, never()).findByProjectIdOrderByTriggeredAtDesc(11L);
    }

    @Test
    void bindManagedSubdomain_rejectsSelfReferentialConfiguredTarget() {
        commandService = commandService(new CloudflareProperties(
                null,
                null,
                "qeploy.com",
                "https://my-project.qeploy.com/",
                null,
                null,
                null
        ));
        Project project = boundProject("https://octo.github.io/repo/");
        User user = activeUser();

        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(domainBindingRepository.existsByHostnameIgnoreCase("my-project.qeploy.com")).thenReturn(false);

        assertThatThrownBy(() -> commandService.bindDomain(
                1L,
                11L,
                new BindDomainCommand(DomainType.MANAGED_SUBDOMAIN, "my-project", null, null)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DNS 대상이 자기 자신을 가리킬 수 없습니다");

        verifyNoInteractions(cloudflareDnsPort, hostingCustomDomainPort, githubActionsPort, githubRepoPort);
    }

    private DomainBindingCommandService commandService(CloudflareProperties cloudflareProperties) {
        return new DomainBindingCommandService(
                projectRepository,
                deploymentHistoryRepository,
                userRepository,
                authCommandService,
                domainBindingRepository,
                cloudflareDnsPort,
                dnsLookupPort,
                hostingCustomDomainPort,
                githubActionsPort,
                githubRepoPort,
                cloudflareProperties
        );
    }

    private Project boundProject(String currentUrl) {
        LocalDateTime now = LocalDateTime.now();
        return new Project(
                11L,
                1L,
                "my-project",
                ProjectStatus.ACTIVE,
                "blank",
                "vue",
                "fast",
                DeployStatus.LIVE,
                currentUrl,
                "v1.0.0",
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

    private User activeUser() {
        return new User(
                1L,
                new GithubId("123"),
                "octo",
                null,
                100L,
                "user-token",
                "refresh-token",
                LocalDateTime.now().plusHours(1)
        );
    }
}
