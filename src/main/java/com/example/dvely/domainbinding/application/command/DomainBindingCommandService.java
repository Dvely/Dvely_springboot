package com.example.dvely.domainbinding.application.command;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.exception.NotFoundException;
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
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import java.net.IDN;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainBindingCommandService {

    private static final String MAIN_BRANCH = "main";
    private static final int WORKFLOW_TRIGGER_MAX_RETRY = 5;
    private static final long WORKFLOW_TRIGGER_RETRY_INTERVAL_MS = 3000;

    private final ProjectRepository projectRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final DomainBindingRepository domainBindingRepository;
    private final CloudflareDnsPort cloudflareDnsPort;
    private final DnsLookupPort dnsLookupPort;
    private final HostingCustomDomainPort hostingCustomDomainPort;
    private final GithubActionsPort githubActionsPort;
    private final GithubRepoPort githubRepoPort;
    private final CloudflareProperties cloudflareProperties;

    @Transactional
    public DomainBindingResult bindDomain(Long ownerUserId, Long projectId, BindDomainCommand command) {
        Project project = resolveProject(ownerUserId, projectId);
        if (command.type() == DomainType.MANAGED_SUBDOMAIN) {
            User user = resolveUser(ownerUserId);
            return bindManagedSubdomain(project, user, command);
        }
        if (command.type() == DomainType.CUSTOM_DOMAIN) {
            User user = resolveUser(ownerUserId);
            return bindCustomDomain(project, user, command);
        }
        throw new IllegalArgumentException("구매형 도메인 연결은 아직 외부 registrar 연동 후 지원됩니다.");
    }

    @Transactional
    public DomainBindingResult checkVerification(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        Project project = resolveProject(ownerUserId, domain.getProjectId());
        User user = resolveUser(ownerUserId);
        boolean connected = switch (domain.getType()) {
            case MANAGED_SUBDOMAIN -> cloudflareDnsPort.recordExists(
                    domain.getHostname(),
                    domain.getCloudflareRecordId()
            ) && isPagesCustomDomainConfigured(user, project, domain.getHostname());
            case CUSTOM_DOMAIN -> isCustomDomainConnected(domain)
                    && isPagesCustomDomainConfigured(user, project, domain.getHostname());
            case PURCHASABLE_DOMAIN -> throw new IllegalArgumentException(
                    "구매형 도메인 검증은 아직 지원되지 않습니다. domainId=" + domainId);
        };
        domain.markVerificationChecked(connected);
        return toResult(domainBindingRepository.save(domain));
    }

    @Transactional
    public void deleteDomain(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        Project project = resolveProject(ownerUserId, domain.getProjectId());
        User user = resolveUser(ownerUserId);
        removePagesCustomDomainIfMatches(user, project, domain.getHostname());
        if (domain.getType() == DomainType.MANAGED_SUBDOMAIN) {
            cloudflareDnsPort.deleteRecord(domain.getHostname(), domain.getCloudflareRecordId());
        }
        domainBindingRepository.deleteById(domain.getId());
    }

    private DomainBindingResult bindManagedSubdomain(Project project, User user, BindDomainCommand command) {
        String label = normalizeLabel(command.label());
        String hostname = label + "." + cloudflareProperties.managedDomainOrDefault();
        ensureHostnameAvailable(hostname);
        String dnsTarget = resolveManagedDnsTarget(project);
        ensureDnsTargetDoesNotReferenceHostname(hostname, dnsTarget);
        String deploymentRepository = resolveDeploymentRepository(project);
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.MANAGED_SUBDOMAIN,
                hostname,
                DomainStatus.PROVISIONING,
                VerificationMethod.CNAME,
                dnsTarget
        );
        String recordId = cloudflareDnsPort.createCnameRecord(hostname, dnsTarget);
        try {
            hostingCustomDomainPort.setCustomDomain(user.getGithubUserAccessToken(), deploymentRepository, hostname);
            domain.assignCloudflareRecord(recordId);
            triggerPagesRebuildForCustomDomain(project, user);
        } catch (RuntimeException e) {
            cloudflareDnsPort.deleteRecord(hostname, recordId);
            throw e;
        }
        return toResult(domainBindingRepository.save(domain));
    }

    private DomainBindingResult bindCustomDomain(Project project, User user, BindDomainCommand command) {
        String hostname = normalizeHostname(command.hostname());
        ensureHostnameAvailable(hostname);
        VerificationMethod method = command.verificationMethod() == null
                ? VerificationMethod.CNAME
                : command.verificationMethod();
        String dnsTarget = resolveDeploymentDnsTarget(project);
        ensureDnsTargetDoesNotReferenceHostname(hostname, dnsTarget);
        hostingCustomDomainPort.setCustomDomain(
                user.getGithubUserAccessToken(),
                resolveDeploymentRepository(project),
                hostname
        );
        triggerPagesRebuildForCustomDomain(project, user);
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.CUSTOM_DOMAIN,
                hostname,
                DomainStatus.VERIFYING,
                method,
                dnsTarget
        );
        return toResult(domainBindingRepository.save(domain));
    }

    private boolean isCustomDomainConnected(DomainBinding domain) {
        if (domain.getVerificationMethod() == VerificationMethod.A) {
            return dnsLookupPort.hasAddressRecordMatching(domain.getHostname(), domain.getDnsTarget());
        }
        return dnsLookupPort.hasCname(domain.getHostname(), domain.getDnsTarget());
    }

    private void ensureHostnameAvailable(String hostname) {
        if (domainBindingRepository.existsByHostnameIgnoreCase(hostname)) {
            throw new IllegalArgumentException("이미 연결된 도메인입니다: " + hostname);
        }
    }

    private Project resolveProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "Project not found. projectId=" + projectId + ", ownerUserId=" + ownerUserId));
    }

    private User resolveUser(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다. userId=" + ownerUserId));

        if (user.isUserAccessTokenExpired()) {
            authCommandService.refreshGithubUserToken(ownerUserId);
            user = userRepository.findById(ownerUserId)
                    .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        }

        if (user.getGithubUserAccessToken() == null || user.getGithubUserAccessToken().isBlank()) {
            throw new IllegalArgumentException("GitHub App User Token이 없습니다. GitHub App 권한을 갱신해 주세요.");
        }
        return user;
    }

    private DomainBinding resolveDomainOwnedBy(Long domainId, Long ownerUserId) {
        DomainBinding domain = domainBindingRepository.findById(domainId)
                .orElseThrow(() -> new NotFoundException("도메인을 찾을 수 없습니다. domainId=" + domainId));
        resolveProject(ownerUserId, domain.getProjectId());
        return domain;
    }

    private String resolveManagedDnsTarget(Project project) {
        if (cloudflareProperties.hasManagedTarget()) {
            return normalizeDnsTarget(cloudflareProperties.managedTargetOrNull());
        }
        return resolveDeploymentDnsTarget(project);
    }

    private String resolveDeploymentDnsTarget(Project project) {
        String deployedUrl = resolveDeploymentUrl(project);
        String githubPagesHost = resolveGithubPagesHost(project.getDeploymentRepository());
        if (githubPagesHost != null) {
            return githubPagesHost;
        }

        return normalizeDnsTarget(deployedUrl);
    }

    private String resolveDeploymentUrl(Project project) {
        if (project.getCurrentUrl() != null && !project.getCurrentUrl().isBlank()) {
            return project.getCurrentUrl();
        }
        return deploymentHistoryRepository.findByProjectIdOrderByTriggeredAtDesc(project.getId()).stream()
                .filter(history -> history.getStatus() == DeployStatus.LIVE)
                .findFirst()
                .map(history -> history.getDeployedUrl())
                .filter(url -> url != null && !url.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "도메인을 연결할 배포 URL이 없습니다. 먼저 배포를 완료해 주세요."));
    }

    private String resolveDeploymentRepository(Project project) {
        String deploymentRepository = project.getDeploymentRepository();
        if (deploymentRepository == null || deploymentRepository.isBlank()) {
            throw new IllegalArgumentException(
                    "호스팅 custom domain을 설정할 배포 저장소가 없습니다. projectId=" + project.getId());
        }
        return deploymentRepository;
    }

    private String resolveGithubPagesHost(String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            return null;
        }
        String[] parts = repositoryFullName.trim().split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repositoryFullName);
        }
        return parts[0].trim().toLowerCase() + ".github.io";
    }

    private void ensureDnsTargetDoesNotReferenceHostname(String hostname, String dnsTarget) {
        if (normalizeDnsName(hostname).equals(normalizeDnsName(dnsTarget))) {
            throw new IllegalArgumentException("DNS 대상이 자기 자신을 가리킬 수 없습니다: " + hostname);
        }
    }

    private boolean isPagesCustomDomainConfigured(User user, Project project, String hostname) {
        return hostingCustomDomainPort.isCustomDomainConfigured(
                user.getGithubUserAccessToken(),
                resolveDeploymentRepository(project),
                hostname
        );
    }

    private void removePagesCustomDomainIfMatches(User user, Project project, String hostname) {
        hostingCustomDomainPort.removeCustomDomainIfMatches(
                user.getGithubUserAccessToken(),
                resolveDeploymentRepository(project),
                hostname
        );
    }

    private void triggerPagesRebuildForCustomDomain(Project project, User user) {
        try {
            String sourceRepository = project.getSourceRepository();
            if (sourceRepository == null || sourceRepository.isBlank()) {
                log.warn("custom domain 적용 후 Pages 재배포 생략: sourceRepository 없음, projectId={}", project.getId());
                return;
            }
            String userToken = user.getGithubUserAccessToken();
            PackageManager packageManager = githubRepoPort.detectPackageManager(userToken, sourceRepository);
            String nodeVersion = githubRepoPort.detectNodeVersion(userToken, sourceRepository);
            String detectedType = githubRepoPort.detectFrameworkType(userToken, sourceRepository);
            String resolvedType = detectedType != null ? detectedType : project.getTemplateType();
            String workflow = DeployWorkflowTemplate.generate(resolvedType, null, packageManager, nodeVersion);

            githubActionsPort.createOrUpdateWorkflow(
                    userToken,
                    sourceRepository,
                    DeployWorkflowTemplate.fileName(),
                    workflow
            );
            triggerWorkflowWithRetry(
                    userToken,
                    sourceRepository,
                    DeployWorkflowTemplate.fileName(),
                    MAIN_BRANCH,
                    resolveCurrentDeploymentRef(project)
            );
            log.info("custom domain 적용 후 Pages 재배포 트리거 완료: projectId={}, repo={}",
                    project.getId(), sourceRepository);
        } catch (RuntimeException e) {
            log.warn("custom domain 적용 후 Pages 재배포 트리거 실패: projectId={}, reason={}",
                    project.getId(), e.getMessage(), e);
        }
    }

    private String resolveCurrentDeploymentRef(Project project) {
        String currentVersion = project.getCurrentVersion();
        if (currentVersion != null && !currentVersion.isBlank()) {
            return currentVersion;
        }
        return MAIN_BRANCH;
    }

    private void triggerWorkflowWithRetry(String userToken, String sourceRepository, String workflowFile,
                                          String dispatchRef, String checkoutRef) {
        for (int attempt = 1; attempt <= WORKFLOW_TRIGGER_MAX_RETRY; attempt++) {
            try {
                githubActionsPort.triggerWorkflow(userToken, sourceRepository, workflowFile, dispatchRef, checkoutRef);
                return;
            } catch (IllegalStateException e) {
                if (attempt == WORKFLOW_TRIGGER_MAX_RETRY) {
                    throw e;
                }
                log.warn("custom domain 적용 후 Pages 재배포 dispatch 재시도 ({}/{}): {}",
                        attempt, WORKFLOW_TRIGGER_MAX_RETRY, e.getMessage());
                try {
                    Thread.sleep(WORKFLOW_TRIGGER_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Pages 재배포 트리거 대기 중 인터럽트 발생", ie);
                }
            }
        }
    }

    private String normalizeDnsTarget(String value) {
        String target = value.trim();
        if (target.contains("://")) {
            URI uri = URI.create(target);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost().toLowerCase();
            }
        }
        int slashIndex = target.indexOf('/');
        if (slashIndex >= 0) {
            target = target.substring(0, slashIndex);
        }
        return target.endsWith(".")
                ? target.substring(0, target.length() - 1).toLowerCase()
                : target.toLowerCase();
    }

    private String normalizeDnsName(String value) {
        return value == null ? "" : normalizeDnsTarget(value);
    }

    private String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        String label = value.trim().toLowerCase();
        if (!label.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")) {
            throw new IllegalArgumentException("도메인 라벨은 영문 소문자, 숫자, 하이픈만 사용할 수 있습니다.");
        }
        return label;
    }

    private String normalizeHostname(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hostname must not be blank");
        }
        String hostname = value.trim().toLowerCase();
        if (hostname.contains("://")) {
            hostname = URI.create(hostname).getHost();
        }
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname must not be blank");
        }
        hostname = hostname.endsWith(".") ? hostname.substring(0, hostname.length() - 1) : hostname;
        hostname = IDN.toASCII(hostname);
        if (hostname.length() > 253 || !hostname.matches("^[a-z0-9]([a-z0-9-\\.]*[a-z0-9])?$")
                || !hostname.contains(".")) {
            throw new IllegalArgumentException("올바른 hostname 형식이 아닙니다: " + value);
        }
        return hostname;
    }

    private DomainBindingResult toResult(DomainBinding domain) {
        return new DomainBindingResult(
                domain.getId(),
                domain.getProjectId(),
                domain.getType(),
                domain.getHostname(),
                domain.getStatus(),
                domain.getVerificationMethod(),
                domain.getDnsTarget(),
                domain.getLastCheckedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
