package com.example.dvely.domainbinding.infrastructure.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.port.out.HostingCustomDomainPort;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GithubPagesDomainHostingAdapterTest {

    @Mock
    private HostingCustomDomainPort hostingCustomDomainPort;

    @Mock
    private GithubActionsPort githubActionsPort;

    @Mock
    private GithubRepoPort githubRepoPort;

    private GithubPagesDomainHostingAdapter adapter;
    private DomainHostingAdapter.Context context;

    @BeforeEach
    void setUp() {
        adapter = new GithubPagesDomainHostingAdapter(
                hostingCustomDomainPort,
                githubActionsPort,
                githubRepoPort
        );
        context = new DomainHostingAdapter.Context(
                "user-token",
                11L,
                "octo/repo",
                "octo/repo",
                "vue",
                "v3",
                "https://octo.github.io/repo/"
        );
    }

    @Test
    void bindsCustomDomainAndRefreshesPagesWorkflow() {
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo"))
                .thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn("vue");

        adapter.bind(context, "www.example.com");

        assertThat(adapter.resolveDnsTarget(context)).isEqualTo("octo.github.io");
        verify(hostingCustomDomainPort).setCustomDomain("user-token", "octo/repo", "www.example.com");
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
                "v3"
        );
    }

    @Test
    void verificationEnforcesHttpsAfterCertificateApproval() {
        LocalDate expiresAt = LocalDate.now().plusMonths(3);
        when(hostingCustomDomainPort.getSiteStatus("user-token", "octo/repo"))
                .thenReturn(new HostingCustomDomainPort.SiteStatus(
                        "www.example.com",
                        false,
                        "approved",
                        expiresAt
                ));

        var status = adapter.verify(context, "www.example.com");

        assertThat(status.domainConfigured()).isTrue();
        assertThat(status.certificateStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(status.httpsEnforced()).isTrue();
        assertThat(status.certificateExpiresAt()).isEqualTo(expiresAt);
        verify(hostingCustomDomainPort).setHttpsEnforced("user-token", "octo/repo", true);
    }
}
