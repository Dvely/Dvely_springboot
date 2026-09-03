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
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
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

    @Mock
    private HttpsProbePort httpsProbePort;

    private GithubPagesDomainHostingAdapter adapter;
    private DomainHostingAdapter.Context context;

    @BeforeEach
    void setUp() {
        adapter = new GithubPagesDomainHostingAdapter(
                hostingCustomDomainPort,
                githubActionsPort,
                githubRepoPort,
                httpsProbePort
        );
        context = new DomainHostingAdapter.Context(
                "user-token",
                11L,
                "octo/repo",
                "octo/repo",
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

    /**
     * 프레임워크 감지가 실패해도 프로젝트의 콘텐츠 템플릿 값으로 폴백하지 않는다. Context 가
     * templateType 을 아예 담지 않게 바꾼 것이 그 보장이고, 이 테스트는 그때의 동작을 고정한다 —
     * 감지 실패는 기본값(./dist)으로 떨어진다.
     */
    @Test
    void undetectedFrameworkFallsBackToDefaultPublishDirNotAContentTemplate() {
        when(githubRepoPort.detectPackageManager("user-token", "octo/repo"))
                .thenReturn(PackageManager.NPM);
        when(githubRepoPort.detectNodeVersion("user-token", "octo/repo")).thenReturn("20");
        when(githubRepoPort.detectFrameworkType("user-token", "octo/repo")).thenReturn(null);

        adapter.bind(context, "www.example.com");

        verify(githubActionsPort).createOrUpdateWorkflow(
                org.mockito.ArgumentMatchers.eq("user-token"),
                org.mockito.ArgumentMatchers.eq("octo/repo"),
                org.mockito.ArgumentMatchers.eq(DeployWorkflowTemplate.fileName()),
                contains("publish_dir: ./dist")
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

    @Test
    void verificationMarksHttpsEnforcedWhenProxiedDomainServesHttpsDespiteGithubPending() {
        // Cloudflare 프록시 도메인: GitHub 은 자기 인증서를 검증 못 해 상태가 PENDING 으로 남지만
        // 엣지 인증서로 실제 https 는 된다. 프로브 성공 → httpsEnforced 상향 보정, certificateStatus 는
        // GitHub 관점(PENDING) 유지, GitHub setHttpsEnforced API 는 호출하지 않는다(프록시라 무의미).
        when(hostingCustomDomainPort.getSiteStatus("user-token", "octo/repo"))
                .thenReturn(new HostingCustomDomainPort.SiteStatus(
                        "www.example.com",
                        false,
                        null,      // GitHub 이 인증서를 못 봄 → PENDING
                        null
                ));
        when(httpsProbePort.isHttpsServing("www.example.com")).thenReturn(true);

        var status = adapter.verify(context, "www.example.com");

        assertThat(status.domainConfigured()).isTrue();
        assertThat(status.httpsEnforced()).isTrue();                       // 실측으로 보정
        assertThat(status.certificateStatus()).isEqualTo(CertificateStatus.PENDING);  // GitHub 관점 유지
        verify(hostingCustomDomainPort, org.mockito.Mockito.never())
                .setHttpsEnforced(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void verificationLeavesHttpsFalseWhenNeitherGithubNorProbeConfirms() {
        // GitHub 도 아직이고 실제 https 도 아직: 프로브 실패 → httpsEnforced=false 유지.
        when(hostingCustomDomainPort.getSiteStatus("user-token", "octo/repo"))
                .thenReturn(new HostingCustomDomainPort.SiteStatus(
                        "www.example.com",
                        false,
                        null,
                        null
                ));
        when(httpsProbePort.isHttpsServing("www.example.com")).thenReturn(false);

        var status = adapter.verify(context, "www.example.com");

        assertThat(status.httpsEnforced()).isFalse();
    }
}
