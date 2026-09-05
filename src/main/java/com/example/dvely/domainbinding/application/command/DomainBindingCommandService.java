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
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort;
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
import com.example.dvely.project.domain.value.FrontendHostingType;
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
    private final S3CdnProvisioningPort s3CdnProvisioningPort;

    @Transactional
    public DomainBindingResult bindDomain(Long ownerUserId, Long projectId, BindDomainCommand command) {
        Project project = resolveProject(ownerUserId, projectId);
        DomainBindingResult result;
        if (command.hostingTarget() == DomainHostingTarget.AWS_S3_FRONTEND) {
            // S3 프론트 HTTPS 는 CloudFront+ACM 비동기 프로비저닝이라 다른 경로다: 인증서만 요청해
            // PROVISIONING 으로 두고, 워커가 검증 CNAME→배포 생성→최종 CNAME→https 확인을 진행한다.
            result = bindS3Frontend(project, command);
        } else {
            DomainHostingAdapter adapter = hostingAdapterRegistry.resolve(command.hostingTarget());
            // GitHub Pages(프론트)만 사용자 GitHub 토큰이 필요하다(Pages 커스텀도메인 API). AWS 백엔드는
            // 그 게이트를 안 거친다 — 안 그러면 GitHub 토큰 없는 사용자가 백엔드 도메인도 못 붙인다.
            User user = requiresGithubToken(command.hostingTarget()) ? resolveUser(ownerUserId) : null;
            DomainHostingAdapter.Context context = toHostingContext(user, project);
            if (command.type() == DomainType.MANAGED_SUBDOMAIN) {
                result = bindManagedSubdomain(project, command, adapter, context);
            } else if (command.type() == DomainType.CUSTOM_DOMAIN) {
                result = bindCustomDomain(project, command, adapter, context);
            } else {
                throw new IllegalArgumentException("구매형 도메인 연결은 아직 외부 registrar 연동 후 지원됩니다.");
            }
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
        if (domain.getHostingTarget() == DomainHostingTarget.AWS_S3_FRONTEND) {
            return verifyS3Frontend(domain);
        }
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
        if (domain.getHostingTarget() == DomainHostingTarget.AWS_S3_FRONTEND) {
            teardownS3Domain(domain);
        } else {
            Project project = resolveProject(ownerUserId, domain.getProjectId());
            User user = resolveUser(ownerUserId);
            hostingAdapterRegistry.resolve(domain.getHostingTarget())
                    .unbind(toHostingContext(user, project), domain.getHostname());
            if (domain.getType() == DomainType.MANAGED_SUBDOMAIN) {
                cloudflareDnsPort.deleteRecord(domain.getHostname(), domain.getCloudflareRecordId());
            }
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
        // EC2 대상(백엔드 AWS · 독립 프론트 AWS_EC2_FRONTEND)은 대상이 IP(EIP)라 CNAME 을 못 건다 —
        // A 레코드로 EIP 를 가리키고 HTTPS 는 인스턴스 Caddy 가 종단한다(proxied=false, Cloudflare 프록시 미경유).
        // 관리형 호스트(GitHub Pages 등)는 그대로 CNAME.
        boolean ec2ATarget = isEc2Target(command.hostingTarget());
        VerificationMethod method = ec2ATarget ? VerificationMethod.A : VerificationMethod.CNAME;
        DomainBinding domain = new DomainBinding(
                project.getId(),
                DomainType.MANAGED_SUBDOMAIN,
                command.hostingTarget(),
                hostname,
                DomainStatus.PROVISIONING,
                method,
                dnsTarget
        );
        String recordId = ec2ATarget
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
        // EC2 대상(백엔드·독립 프론트)은 대상이 IP(EIP)라 사용자가 A 레코드를 EIP 로 건다 → 검증도 A 가 기본.
        // 관리형 호스트(GitHub Pages 등)는 CNAME 기본. 클라이언트가 명시하면 그게 우선.
        VerificationMethod method = command.verificationMethod() != null
                ? command.verificationMethod()
                : (isEc2Target(command.hostingTarget())
                        ? VerificationMethod.A : VerificationMethod.CNAME);
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
     * S3 프론트 HTTPS 바인딩 시작. CloudFront+ACM 은 비동기라 여기선 인증서 발급만 요청하고 PROVISIONING
     * 으로 저장한다 — 워커가 검증 CNAME → 배포 생성 → 최종 CNAME → https 확인을 진행한다. S3 로 배포된
     * 프로젝트만 오리진(버킷)이 있어 허용한다.
     */
    private DomainBindingResult bindS3Frontend(Project project, BindDomainCommand command) {
        if (project.getFrontendHostingType() != FrontendHostingType.S3) {
            throw new IllegalStateException(
                    "S3 프론트 호스팅으로 설정한 프로젝트에서만 이 도메인 연결을 쓸 수 있습니다. 프론트를 S3 로 먼저 배포해주세요.");
        }
        // 이 단계는 관리형 서브도메인(우리 Cloudflare 존, DNS 완전 자동)만 지원한다. 커스텀 도메인은
        // ACM 검증 CNAME·최종 CNAME 을 사용자 소유 존에 넣어야 해(우리 토큰 밖) 사용자 DNS 안내가 필요 —
        // 후속으로 뺀다.
        if (command.type() != DomainType.MANAGED_SUBDOMAIN) {
            throw new IllegalArgumentException(
                    "S3 프론트 HTTPS 는 현재 관리형 서브도메인만 지원합니다(커스텀 도메인은 곧 지원).");
        }
        String hostname = normalizeLabel(command.label()) + "." + cloudflareProperties.managedDomainOrDefault();
        ensureHostnameAvailable(hostname);
        // ACM 인증서(us-east-1) 발급 요청 — 최종 DNS 는 CloudFront 도메인으로의 CNAME(워커가 배포 후 설정).
        String certArn = s3CdnProvisioningPort.requestCertificate(project.getId(), hostname);
        DomainBinding domain = new DomainBinding(
                project.getId(),
                command.type(),
                DomainHostingTarget.AWS_S3_FRONTEND,
                hostname,
                DomainStatus.PROVISIONING,
                VerificationMethod.CNAME,
                null);
        domain.assignAcmCertificate(certArn);
        return toResult(domainBindingRepository.save(domain));
    }

    /**
     * S3 프론트 도메인 검증. 워커가 주도하지만 수동 재검증도 같은 판단을 쓴다: 실제 https 가 서빙되면
     * CONNECTED 로 올리고, 아니면 PROVISIONING 을 유지한다(VERIFYING 으로 내리지 않는다 — 그러면 EC2/Pages
     * 용 DomainVerificationWorker 와 소유가 겹친다).
     */
    private DomainBindingResult verifyS3Frontend(DomainBinding domain) {
        DomainHostingAdapter.VerificationStatus status = hostingAdapterRegistry
                .resolve(DomainHostingTarget.AWS_S3_FRONTEND)
                .verify(null, domain.getHostname());   // Context 불필요(https 프로브만)
        if (status.httpsEnforced()) {
            domain.markVerificationChecked(true, true,
                    status.certificateStatus(), status.certificateExpiresAt());
            domainBindingRepository.save(domain);
        }
        return toResult(domain);
    }

    /**
     * 프로젝트 삭제 시 그 프로젝트의 S3 프론트 도메인을 정리한다(Cloudflare 레코드·CloudFront·인증서).
     * 시스템 내부 호출이라 소유권 검사는 상위(프로젝트 삭제)가 이미 했다. 한 도메인 정리가 실패해도
     * 나머지는 계속한다(best-effort).
     */
    @Transactional
    public void cleanupProjectS3Domains(Long projectId) {
        for (DomainBinding domain : domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (domain.getHostingTarget() != DomainHostingTarget.AWS_S3_FRONTEND) {
                continue;
            }
            try {
                teardownS3Domain(domain);
                domainBindingRepository.deleteById(domain.getId());
                log.info("프로젝트 삭제로 S3 프론트 도메인 정리: hostname={} projectId={}",
                        domain.getHostname(), projectId);
            } catch (RuntimeException e) {
                log.warn("프로젝트 삭제 시 S3 도메인 정리 실패(무시): hostname={} 원인={}",
                        domain.getHostname(), e.toString());
            }
        }
    }

    /**
     * S3 프론트 도메인 정리: Cloudflare 레코드(최종 CNAME·ACM 검증 CNAME)를 지우고, CloudFront 배포·인증서
     * 정리를 큐잉한다(배포 삭제는 Deployed 후 리퍼가 마무리). 배포가 아직 없으면 인증서만 바로 지운다.
     * 각 단계는 best-effort — 실패가 나머지·행 삭제를 막지 않는다(dangling DNS/고아 자원 방지 우선).
     */
    private void teardownS3Domain(DomainBinding domain) {
        deleteCloudflareRecordQuietly(domain.getHostname(), domain.getCloudflareRecordId());
        deleteCloudflareRecordQuietly(domain.getHostname(), domain.getAcmValidationRecordId());
        if (domain.getCloudfrontDistributionId() != null) {
            safeCleanup(() -> s3CdnProvisioningPort.scheduleDistributionCleanup(
                    domain.getProjectId(), domain.getCloudfrontDistributionId(),
                    domain.getAcmCertificateArn(), domain.getHostname()));
        } else if (domain.getAcmCertificateArn() != null) {
            safeCleanup(() -> s3CdnProvisioningPort.deleteCertificate(
                    domain.getProjectId(), domain.getAcmCertificateArn()));
        }
    }

    private void deleteCloudflareRecordQuietly(String hostname, String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return;   // record id 로만 지운다 — 없으면 안전하게 건너뛴다(엉뚱한 동명 레코드 삭제 방지)
        }
        try {
            cloudflareDnsPort.deleteRecord(hostname, recordId);
        } catch (RuntimeException e) {
            log.warn("Cloudflare 레코드 삭제 실패(무시): hostname={} recordId={} 원인={}",
                    hostname, recordId, e.toString());
        }
    }

    private void safeCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.warn("S3 CDN 정리 큐잉 실패(무시): {}", e.toString());
        }
    }

    /**
     * EC2 서버 종료로 EIP 가 해제될 때, 그 IP 를 가리키던 이 프로젝트의 EC2 도메인(백엔드 AWS · 독립
     * 프론트 AWS_EC2_FRONTEND)을 정리한다. 백엔드·프론트 어느 서버가 종료되든 해제되는 EIP 를 dnsTarget
     * 으로 삼던 도메인만 골라 지우므로, 프론트 서버 종료 시 프론트 도메인이 함께 정리된다.
     * <b>보안:</b> 해제된 EIP 는 AWS 풀로 돌아가 남에게 재할당될 수 있어, Cloudflare A 레코드를 남겨두면
     * 우리 서브도메인이 남의 서버를 가리키는 dangling DNS(서브도메인 탈취)가 된다 — 그래서 레코드를 반드시
     * 지운다. 시스템 내부 호출(종료 정리)이라 소유권 검사는 상위(terminate)가 이미 했다. 한 도메인 정리가
     * 실패해도 나머지·종료는 계속한다(best-effort).
     */
    @Transactional
    public void releaseServerDomains(Long projectId, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(d -> isEc2Target(d.getHostingTarget()))
                .filter(d -> ipAddress.equals(d.getDnsTarget()))
                .forEach(d -> {
                    try {
                        if (d.getCloudflareRecordId() != null && !d.getCloudflareRecordId().isBlank()) {
                            cloudflareDnsPort.deleteRecord(d.getHostname(), d.getCloudflareRecordId());
                        }
                        domainBindingRepository.deleteById(d.getId());
                        log.info("서버 종료로 도메인 정리: hostname={} projectId={} target={} (EIP {} 해제)",
                                d.getHostname(), projectId, d.getHostingTarget(), ipAddress);
                    } catch (RuntimeException e) {
                        log.warn("서버 종료 시 도메인 정리 실패(수동 확인 필요, dangling DNS 위험): hostname={} 원인={}",
                                d.getHostname(), e.toString());
                    }
                });
    }

    /** EC2 인스턴스(백엔드 AWS · 독립 프론트 AWS_EC2_FRONTEND)에 EIP·Caddy 로 붙는 대상인가. */
    private static boolean isEc2Target(DomainHostingTarget target) {
        return target == DomainHostingTarget.AWS || target == DomainHostingTarget.AWS_EC2_FRONTEND;
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
