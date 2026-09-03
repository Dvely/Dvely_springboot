package com.example.dvely.domainbinding.infrastructure.hosting;

import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.port.out.HostingCustomDomainPort;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubPagesDomainHostingAdapter implements DomainHostingAdapter {

    private static final String MAIN_BRANCH = "main";
    private static final int WORKFLOW_TRIGGER_MAX_RETRY = 5;
    private static final long WORKFLOW_TRIGGER_RETRY_INTERVAL_MS = 3000;

    private final HostingCustomDomainPort hostingCustomDomainPort;
    private final GithubActionsPort githubActionsPort;
    private final GithubRepoPort githubRepoPort;
    private final HttpsProbePort httpsProbePort;

    @Override
    public DomainHostingTarget target() {
        return DomainHostingTarget.GITHUB_PAGES;
    }

    @Override
    public String resolveDnsTarget(Context context) {
        String repository = requireRepository(context.deploymentRepository());
        String[] parts = repository.split("/", 2);
        return parts[0].trim().toLowerCase(Locale.ROOT) + ".github.io";
    }

    @Override
    public void bind(Context context, String hostname) {
        String repository = requireRepository(context.deploymentRepository());
        hostingCustomDomainPort.setCustomDomain(context.userToken(), repository, hostname);
        triggerPagesRebuild(context);
    }

    @Override
    public VerificationStatus verify(Context context, String hostname) {
        String repository = requireRepository(context.deploymentRepository());
        HostingCustomDomainPort.SiteStatus site =
                hostingCustomDomainPort.getSiteStatus(context.userToken(), repository);
        CertificateStatus certificateStatus = toCertificateStatus(site.certificateState());
        boolean httpsEnforced = site.httpsEnforced();
        if (site.hasCustomDomain(hostname)
                && certificateStatus == CertificateStatus.ACTIVE
                && !httpsEnforced) {
            hostingCustomDomainPort.setHttpsEnforced(context.userToken(), repository, true);
            httpsEnforced = true;
        }
        // Cloudflare 프록시를 태운 도메인은 GitHub 이 자기 인증서를 검증 못 해 certificateState 가
        // 영원히 PENDING 이지만, 엣지 인증서로 실제 https 는 된다. 실제 https 프로브가 성공하면
        // httpsEnforced 를 실상대로 true 로 올린다(상향 보정만). certificateStatus 는 GitHub 관점의
        // 값이라 그대로 둔다 — 그 필드는 "GitHub 인증서 상태"라는 자기 뜻에 충실해야 하고, 화면엔
        // httpsEnforced 만 노출한다(프록시라 GitHub setHttpsEnforced API 는 부르지 않는다 — 무의미).
        if (!httpsEnforced && httpsProbePort.isHttpsServing(hostname)) {
            httpsEnforced = true;
        }
        return new VerificationStatus(
                site.hasCustomDomain(hostname),
                httpsEnforced,
                certificateStatus,
                site.certificateExpiresAt()
        );
    }

    @Override
    public void unbind(Context context, String hostname) {
        hostingCustomDomainPort.removeCustomDomainIfMatches(
                context.userToken(),
                requireRepository(context.deploymentRepository()),
                hostname
        );
    }

    private void triggerPagesRebuild(Context context) {
        try {
            String sourceRepository = context.sourceRepository();
            if (sourceRepository == null || sourceRepository.isBlank()) {
                log.warn("custom domain 적용 후 Pages 재배포 생략: sourceRepository 없음, projectId={}",
                        context.projectId());
                return;
            }
            PackageManager packageManager =
                    githubRepoPort.detectPackageManager(context.userToken(), sourceRepository);
            String nodeVersion = githubRepoPort.detectNodeVersion(context.userToken(), sourceRepository);
            // 프레임워크는 저장소 감지 결과만 쓴다. 예전에는 감지 실패 시 context.templateType()
            // 으로 폴백했는데, 그 값은 프레임워크가 아니라 사용자가 고른 콘텐츠 템플릿이라
            // publish 디렉터리를 잘못 고를 수 있었다. 감지 실패 시 null 은 generate() 의
            // 기본값(./dist)으로 떨어진다.
            String detectedType = githubRepoPort.detectFrameworkType(context.userToken(), sourceRepository);
            String workflow = DeployWorkflowTemplate.generate(detectedType, null, packageManager, nodeVersion);

            githubActionsPort.createOrUpdateWorkflow(
                    context.userToken(),
                    sourceRepository,
                    DeployWorkflowTemplate.fileName(),
                    workflow
            );
            triggerWorkflowWithRetry(
                    context.userToken(),
                    sourceRepository,
                    DeployWorkflowTemplate.fileName(),
                    MAIN_BRANCH,
                    context.currentVersion() == null || context.currentVersion().isBlank()
                            ? MAIN_BRANCH
                            : context.currentVersion()
            );
        } catch (RuntimeException exception) {
            log.warn("custom domain 적용 후 Pages 재배포 트리거 실패: projectId={}, reason={}",
                    context.projectId(), exception.getMessage(), exception);
        }
    }

    private void triggerWorkflowWithRetry(String userToken,
                                          String sourceRepository,
                                          String workflowFile,
                                          String dispatchRef,
                                          String checkoutRef) {
        for (int attempt = 1; attempt <= WORKFLOW_TRIGGER_MAX_RETRY; attempt++) {
            try {
                githubActionsPort.triggerWorkflow(
                        userToken,
                        sourceRepository,
                        workflowFile,
                        dispatchRef,
                        checkoutRef
                );
                return;
            } catch (IllegalStateException exception) {
                if (attempt == WORKFLOW_TRIGGER_MAX_RETRY) {
                    throw exception;
                }
                try {
                    Thread.sleep(WORKFLOW_TRIGGER_RETRY_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Pages 재배포 트리거 대기 중 인터럽트 발생", interrupted);
                }
            }
        }
    }

    private CertificateStatus toCertificateStatus(String state) {
        if (state == null || state.isBlank()) {
            return CertificateStatus.PENDING;
        }
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        if ("approved".equals(normalized) || "active".equals(normalized)) {
            return CertificateStatus.ACTIVE;
        }
        if (normalized.contains("fail")
                || normalized.contains("error")
                || normalized.contains("reject")) {
            return CertificateStatus.FAILED;
        }
        return CertificateStatus.PROVISIONING;
    }

    private String requireRepository(String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            throw new IllegalArgumentException("GitHub Pages 도메인을 설정할 배포 저장소가 없습니다.");
        }
        String[] parts = repositoryFullName.trim().split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repositoryFullName);
        }
        return repositoryFullName.trim();
    }
}
