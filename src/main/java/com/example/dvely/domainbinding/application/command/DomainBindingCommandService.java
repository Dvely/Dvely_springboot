package com.example.dvely.domainbinding.application.command;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.application.port.out.DnsLookupPort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.service.DomainHostingAdapterRegistry;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DomainBindingCommandService {

    private final ProjectRepository projectRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final DomainBindingRepository domainBindingRepository;
    private final CloudflareDnsPort cloudflareDnsPort;
    private final DnsLookupPort dnsLookupPort;
    private final DomainHostingAdapterRegistry hostingAdapterRegistry;
    private final CloudflareProperties cloudflareProperties;

    @Transactional
    public DomainBindingResult bindDomain(Long ownerUserId, Long projectId, BindDomainCommand command) {
        Project project = resolveProject(ownerUserId, projectId);
        DomainHostingAdapter adapter = hostingAdapterRegistry.resolve(command.hostingTarget());
        User user = resolveUser(ownerUserId);
        DomainHostingAdapter.Context context = toHostingContext(user, project);
        if (command.type() == DomainType.MANAGED_SUBDOMAIN) {
            return bindManagedSubdomain(project, command, adapter, context);
        }
        if (command.type() == DomainType.CUSTOM_DOMAIN) {
            return bindCustomDomain(project, command, adapter, context);
        }
        throw new IllegalArgumentException("구매형 도메인 연결은 아직 외부 registrar 연동 후 지원됩니다.");
    }

    @Transactional
    public DomainBindingResult checkVerification(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        Project project = resolveProject(ownerUserId, domain.getProjectId());
        User user = resolveUser(ownerUserId);
        DomainHostingAdapter adapter = hostingAdapterRegistry.resolve(domain.getHostingTarget());
        DomainHostingAdapter.VerificationStatus hostingStatus =
                adapter.verify(toHostingContext(user, project), domain.getHostname());
        boolean dnsConnected = switch (domain.getType()) {
            case MANAGED_SUBDOMAIN -> cloudflareDnsPort.recordExists(
                    domain.getHostname(),
                    domain.getCloudflareRecordId()
            );
            case CUSTOM_DOMAIN -> isCustomDomainConnected(domain);
            case PURCHASABLE_DOMAIN -> throw new IllegalArgumentException(
                    "구매형 도메인 검증은 아직 지원되지 않습니다. domainId=" + domainId);
        };
        domain.markVerificationChecked(
                dnsConnected && hostingStatus.domainConfigured(),
                hostingStatus.httpsEnforced(),
                hostingStatus.certificateStatus(),
                hostingStatus.certificateExpiresAt()
        );
        return toResult(domainBindingRepository.save(domain));
    }

    @Transactional
    public void deleteDomain(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        Project project = resolveProject(ownerUserId, domain.getProjectId());
        User user = resolveUser(ownerUserId);
        hostingAdapterRegistry.resolve(domain.getHostingTarget())
                .unbind(toHostingContext(user, project), domain.getHostname());
        if (domain.getType() == DomainType.MANAGED_SUBDOMAIN) {
            cloudflareDnsPort.deleteRecord(domain.getHostname(), domain.getCloudflareRecordId());
        }
        domainBindingRepository.deleteById(domain.getId());
    }

    private DomainBindingResult bindManagedSubdomain(Project project,
                                                     BindDomainCommand command,
                                                     DomainHostingAdapter adapter,
                                                     DomainHostingAdapter.Context context) {
        String label = normalizeLabel(command.label());
        String hostname = label + "." + cloudflareProperties.managedDomainOrDefault();
        ensureHostnameAvailable(hostname);
        String dnsTarget = resolveManagedDnsTarget(adapter, context);
        ensureDnsTargetDoesNotReferenceHostname(hostname, dnsTarget);
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.MANAGED_SUBDOMAIN,
                command.hostingTarget(),
                hostname,
                DomainStatus.PROVISIONING,
                VerificationMethod.CNAME,
                dnsTarget
        );
        String recordId = cloudflareDnsPort.createCnameRecord(hostname, dnsTarget);
        try {
            adapter.bind(context, hostname);
            domain.assignCloudflareRecord(recordId);
        } catch (RuntimeException e) {
            cloudflareDnsPort.deleteRecord(hostname, recordId);
            throw e;
        }
        return toResult(domainBindingRepository.save(domain));
    }

    private DomainBindingResult bindCustomDomain(Project project,
                                                 BindDomainCommand command,
                                                 DomainHostingAdapter adapter,
                                                 DomainHostingAdapter.Context context) {
        String hostname = normalizeHostname(command.hostname());
        ensureHostnameAvailable(hostname);
        VerificationMethod method = command.verificationMethod() == null
                ? VerificationMethod.CNAME
                : command.verificationMethod();
        String dnsTarget = adapter.resolveDnsTarget(context);
        ensureDnsTargetDoesNotReferenceHostname(hostname, dnsTarget);
        adapter.bind(context, hostname);
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.CUSTOM_DOMAIN,
                command.hostingTarget(),
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

    private String resolveManagedDnsTarget(DomainHostingAdapter adapter,
                                           DomainHostingAdapter.Context context) {
        if (cloudflareProperties.hasManagedTarget()) {
            return normalizeDnsTarget(cloudflareProperties.managedTargetOrNull());
        }
        return adapter.resolveDnsTarget(context);
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
                .orElse(null);
    }

    private void ensureDnsTargetDoesNotReferenceHostname(String hostname, String dnsTarget) {
        if (normalizeDnsName(hostname).equals(normalizeDnsName(dnsTarget))) {
            throw new IllegalArgumentException("DNS 대상이 자기 자신을 가리킬 수 없습니다: " + hostname);
        }
    }

    private DomainHostingAdapter.Context toHostingContext(User user, Project project) {
        return new DomainHostingAdapter.Context(
                user.getGithubUserAccessToken(),
                project.getId(),
                project.getSourceRepository(),
                project.getDeploymentRepository(),
                project.getTemplateType(),
                project.getCurrentVersion(),
                resolveDeploymentUrl(project)
        );
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
                domain.getHostingTarget(),
                domain.getHostname(),
                domain.getStatus(),
                domain.getVerificationMethod(),
                domain.getDnsTarget(),
                domain.isHttpsEnforced(),
                domain.getCertificateStatus(),
                domain.getCertificateExpiresAt(),
                domain.getLastCheckedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
