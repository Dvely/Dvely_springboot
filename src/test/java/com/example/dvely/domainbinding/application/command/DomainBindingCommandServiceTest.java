package com.example.dvely.domainbinding.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.application.port.out.DnsLookupPort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.service.DomainHostingAdapterRegistry;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
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
import java.time.LocalDate;
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
    private DomainHostingAdapterRegistry hostingAdapterRegistry;

    @Mock
    private DomainHostingAdapter hostingAdapter;

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
        when(hostingAdapterRegistry.resolve(DomainHostingTarget.GITHUB_PAGES))
                .thenReturn(hostingAdapter);
    }

    @Test
    void bindManagedSubdomain_usesGithubPagesHostEvenWhenCurrentUrlIsManagedDomain() {
        Project project = boundProject("https://my-project.qeploy.com/");
        User user = activeUser();

        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(domainBindingRepository.existsByHostnameIgnoreCase("my-project.qeploy.com")).thenReturn(false);
        when(hostingAdapter.resolveDnsTarget(any())).thenReturn("octo.github.io");
        when(cloudflareDnsPort.createCnameRecord("my-project.qeploy.com", "octo.github.io"))
                .thenReturn("cf-record-1");
        when(domainBindingRepository.save(any(DomainBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DomainBindingResult result = commandService.bindDomain(
                1L,
                11L,
                new BindDomainCommand(DomainType.MANAGED_SUBDOMAIN, "my-project", null, null)
        );

        assertThat(result.dnsTarget()).isEqualTo("octo.github.io");
        assertThat(result.status()).isEqualTo(DomainStatus.VERIFYING);
        assertThat(result.hostingTarget()).isEqualTo(DomainHostingTarget.GITHUB_PAGES);
        assertThat(result.certificateStatus()).isEqualTo(CertificateStatus.PENDING);
        verify(cloudflareDnsPort).createCnameRecord("my-project.qeploy.com", "octo.github.io");
        verify(hostingAdapter).bind(any(), org.mockito.ArgumentMatchers.eq("my-project.qeploy.com"));
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

        verifyNoInteractions(cloudflareDnsPort);
        verify(hostingAdapter, never()).bind(any(), any());
    }

    @Test
    void checkVerificationStoresCertificateAndHttpsState() {
        Project project = boundProject("https://octo.github.io/repo/");
        User user = activeUser();
        LocalDateTime now = LocalDateTime.now();
        DomainBinding domain = new DomainBinding(
                31L,
                11L,
                DomainType.MANAGED_SUBDOMAIN,
                DomainHostingTarget.GITHUB_PAGES,
                "my-project.qeploy.com",
                DomainStatus.VERIFYING,
                com.example.dvely.domainbinding.domain.value.VerificationMethod.CNAME,
                "octo.github.io",
                "record-1",
                false,
                CertificateStatus.PROVISIONING,
                null,
                now,
                now,
                now
        );
        LocalDate expiresAt = LocalDate.now().plusMonths(3);

        when(domainBindingRepository.findById(31L)).thenReturn(Optional.of(domain));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(hostingAdapter.verify(any(), org.mockito.ArgumentMatchers.eq("my-project.qeploy.com")))
                .thenReturn(new DomainHostingAdapter.VerificationStatus(
                        true,
                        true,
                        CertificateStatus.ACTIVE,
                        expiresAt
                ));
        when(cloudflareDnsPort.recordExists("my-project.qeploy.com", "record-1")).thenReturn(true);
        when(domainBindingRepository.save(domain)).thenReturn(domain);

        DomainBindingResult result = commandService.checkVerification(1L, 31L);

        assertThat(result.status()).isEqualTo(DomainStatus.CONNECTED);
        assertThat(result.httpsEnforced()).isTrue();
        assertThat(result.certificateStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(result.certificateExpiresAt()).isEqualTo(expiresAt);
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
                hostingAdapterRegistry,
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
