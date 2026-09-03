package com.example.dvely.domainbinding.application.command;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
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
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
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

@Service
@Slf4j
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
    private final AuditRecorder auditRecorder;

    @Transactional
    public DomainBindingResult bindDomain(Long ownerUserId, Long projectId, BindDomainCommand command) {
        Project project = resolveProject(ownerUserId, projectId);
        DomainHostingAdapter adapter = hostingAdapterRegistry.resolve(command.hostingTarget());
        // GitHub Pages(프론트)만 사용자 GitHub 토큰이 필요하다(Pages 커스텀도메인 API). AWS 백엔드는
        // 그 게이트를 안 거친다 — 안 그러면 GitHub 토큰 없는 사용자가 백엔드 도메인도 못 붙인다.
        User user = requiresGithubToken(command.hostingTarget()) ? resolveUser(ownerUserId) : null;
        DomainHostingAdapter.Context context = toHostingContext(user, project);
        DomainBindingResult result;
        if (command.type() == DomainType.MANAGED_SUBDOMAIN) {
            result = bindManagedSubdomain(project, command, adapter, context);
        } else if (command.type() == DomainType.CUSTOM_DOMAIN) {
            result = bindCustomDomain(project, command, adapter, context);
        } else {
            throw new IllegalArgumentException("구매형 도메인 연결은 아직 외부 registrar 연동 후 지원됩니다.");
        }
        // H10 (design §4): recorded once the row is durably saved (bindManagedSubdomain/
        // bindCustomDomain's own save already happened by the time result is returned here) —
        // consolidated at this single call site rather than duplicated in both private methods,
        // since both converge on exactly the same audit shape. A failure inside either private
        // method (adapter.bind()/DNS calls) throws before reaching this line, so only an actually
        // successful bind is ever recorded.
        auditRecorder.record(new AuditEvent(
                AuditAction.DOMAIN_BOUND,
                AuditOutcome.SUCCEEDED,
                command.taskId() != null ? AuditActorType.AGENT : AuditActorType.USER,
                ownerUserId,
                projectId,
                "DOMAIN_BINDING",
                String.valueOf(result.domainId()),
                command.taskId(),
                null,
                "type=" + command.type() + ", hostname=" + result.hostname() + ", hostingTarget=" + command.hostingTarget(),
                null
        ));
        return result;
    }

    @Transactional
    public DomainBindingResult checkVerification(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        return verify(domain, resolveProject(ownerUserId, domain.getProjectId()), ownerUserId);
    }

    /**
     * 검증 워커 경로. 요청한 사용자가 없으므로 도메인이 속한 프로젝트에서 소유자를 찾아
     * 같은 검증을 돌린다. 소유권을 확인하는 것이 아니라 검증에 쓸 토큰의 주인을 찾는 것이다.
     */
    @Transactional
    public DomainBindingResult checkVerificationAsSystem(Long domainId) {
        DomainBinding domain = domainBindingRepository.findById(domainId)
                .orElseThrow(() -> new NotFoundException("도메인을 찾을 수 없습니다. domainId=" + domainId));
        Project project = projectRepository.findById(domain.getProjectId())
                .orElseThrow(() -> new NotFoundException(
                        "프로젝트를 찾을 수 없습니다. projectId=" + domain.getProjectId()));
        return verify(domain, project, project.getOwnerUserId());
    }

    /**
     * 더 검증해도 소용없는 도메인을 FAILED 로 닫는다. 잘못된 CNAME 을 넣은 커스텀 도메인처럼
     * 영원히 성공하지 않는 것들이 워커의 외부 API 호출을 무한히 소비하지 않도록 하는 종결점이다.
     */
    @Transactional
    public void abandonVerification(Long domainId) {
        domainBindingRepository.findById(domainId).ifPresent(domain -> {
            domain.fail();
            domainBindingRepository.save(domain);
        });
    }

    private DomainBindingResult verify(DomainBinding domain, Project project, Long ownerUserId) {
        Long domainId = domain.getId();
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

    /** HTTP path — no Agent task (see {@link #deleteDomain(Long, Long, String)}). */
    @Transactional
    public void deleteDomain(Long ownerUserId, Long domainId) {
        deleteDomain(ownerUserId, domainId, null);
    }

    /**
     * @param taskId nullable — non-null only when the Agent-driven delete path (design H11,
     *               ADR-A8) called this; a direct HTTP call always passes null via the 2-arg
     *               overload above.
     */
    @Transactional
    public void deleteDomain(Long ownerUserId, Long domainId, String taskId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        Project project = resolveProject(ownerUserId, domain.getProjectId());
        User user = resolveUser(ownerUserId);
        hostingAdapterRegistry.resolve(domain.getHostingTarget())
                .unbind(toHostingContext(user, project), domain.getHostname());
        if (domain.getType() == DomainType.MANAGED_SUBDOMAIN) {
            cloudflareDnsPort.deleteRecord(domain.getHostname(), domain.getCloudflareRecordId());
        }
        domainBindingRepository.deleteById(domain.getId());
        // H11 (design §4): the row is hard-deleted above — this audit row becomes the only
        // surviving trace of the binding ever having existed (design §2.2 "DOMAIN 카테고리의 실질
        // 가치"), so hostname is captured in detail rather than relying on a resource that will
        // no longer exist to look up.
        auditRecorder.record(new AuditEvent(
                AuditAction.DOMAIN_DELETED,
                AuditOutcome.SUCCEEDED,
                taskId != null ? AuditActorType.AGENT : AuditActorType.USER,
                ownerUserId,
                domain.getProjectId(),
                "DOMAIN_BINDING",
                String.valueOf(domain.getId()),
                taskId,
                null,
                "hostname=" + domain.getHostname(),
                null
        ));
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
        // 백엔드(AWS)는 대상이 IP(EIP)라 CNAME 을 못 건다 — A 레코드로 EIP 를 가리킨다(현재 DNS-only,
        // proxied=false → http://label:8080 직결; HTTPS 는 B 후속). 프론트(GitHub Pages)는 그대로 CNAME.
        boolean backendTarget = command.hostingTarget() == DomainHostingTarget.AWS;
        VerificationMethod method = backendTarget ? VerificationMethod.A : VerificationMethod.CNAME;
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.MANAGED_SUBDOMAIN,
                command.hostingTarget(),
                hostname,
                DomainStatus.PROVISIONING,
                method,
                dnsTarget
        );
        String recordId = backendTarget
                ? cloudflareDnsPort.createARecord(hostname, dnsTarget, false)
                : cloudflareDnsPort.createCnameRecord(hostname, dnsTarget);
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

    /**
     * 백엔드 서버 종료로 EIP 가 해제될 때, 그 IP 를 가리키던 이 프로젝트의 백엔드(AWS) 도메인을 정리한다.
     * <b>보안:</b> 해제된 EIP 는 AWS 풀로 돌아가 남에게 재할당될 수 있어, Cloudflare A 레코드를 남겨두면
     * 우리 서브도메인이 남의 서버를 가리키는 dangling DNS(서브도메인 탈취)가 된다 — 그래서 레코드를 반드시
     * 지운다. 시스템 내부 호출(종료 정리)이라 소유권 검사는 상위(terminate)가 이미 했다. 한 도메인 정리가
     * 실패해도 나머지·종료는 계속한다(best-effort).
     */
    @Transactional
    public void releaseBackendDomains(Long projectId, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(d -> d.getHostingTarget() == DomainHostingTarget.AWS)
                .filter(d -> ipAddress.equals(d.getDnsTarget()))
                .forEach(d -> {
                    try {
                        if (d.getCloudflareRecordId() != null && !d.getCloudflareRecordId().isBlank()) {
                            cloudflareDnsPort.deleteRecord(d.getHostname(), d.getCloudflareRecordId());
                        }
                        domainBindingRepository.deleteById(d.getId());
                        log.info("백엔드 종료로 도메인 정리: hostname={} projectId={} (EIP {} 해제)",
                                d.getHostname(), projectId, ipAddress);
                    } catch (RuntimeException e) {
                        log.warn("백엔드 종료 시 도메인 정리 실패(수동 확인 필요, dangling DNS 위험): hostname={} 원인={}",
                                d.getHostname(), e.toString());
                    }
                });
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

    private boolean requiresGithubToken(DomainHostingTarget target) {
        // GitHub Pages 어댑터만 사용자 GitHub 토큰으로 Pages 커스텀도메인을 설정한다. AWS 백엔드는 불필요.
        return target == DomainHostingTarget.GITHUB_PAGES;
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
                user == null ? null : user.getGithubUserAccessToken(),
                project.getId(),
                project.getSourceRepository(),
                project.getDeploymentRepository(),
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
