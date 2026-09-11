package com.example.dvely.domainbinding.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.paging.CursorPage;
import com.example.dvely.common.paging.CursorPaging;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchCandidateResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.application.result.VerificationRecordResult;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomainBindingQueryService {

    private final ProjectRepository projectRepository;
    private final DomainBindingRepository domainBindingRepository;
    private final CloudflareProperties cloudflareProperties;
    private final S3CdnProvisioningPort s3CdnProvisioningPort;
    private final BackendAddressPort backendAddressPort;

    /**
     * 이 호스트네임이 우리가 관리하는 EC2 도메인(백엔드 AWS · 독립 프론트 AWS_EC2_FRONTEND)으로
     * 등록됐는지. 백엔드든 프론트든 EC2 인스턴스의 Caddy 가 커스텀 도메인 TLS 를 on-demand 발급하기 전
     * 이 값을 물어(ask) 남용을 막는다 — DB 에 등록된 EC2 도메인만 발급 허용. 관리형 *.qeploy.com 은
     * 인스턴스가 로컬로 자기완결 허용하므로 이 ask 를 타지 않고, 여기 걸리는 것은 커스텀 도메인이다.
     */
    public boolean isEc2DomainRegistered(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        String trimmed = hostname.trim();
        return domainBindingRepository.existsByHostnameIgnoreCaseAndHostingTarget(
                        trimmed, DomainHostingTarget.AWS)
                || domainBindingRepository.existsByHostnameIgnoreCaseAndHostingTarget(
                        trimmed, DomainHostingTarget.AWS_EC2_FRONTEND);
    }

    public DomainSearchResult search(String keyword) {
        String label = normalizeLabel(keyword);
        String managedHostname = label + "." + cloudflareProperties.managedDomainOrDefault();
        List<DomainSearchCandidateResult> results = List.of(new DomainSearchCandidateResult(
                DomainType.MANAGED_SUBDOMAIN,
                managedHostname,
                !domainBindingRepository.existsByHostnameIgnoreCase(managedHostname),
                BigDecimal.ZERO,
                "KRW"
        ));
        return new DomainSearchResult(label, results);
    }

    /**
     * limit 를 안 받는 호출(프로젝트 개요·활동로그)의 상한. 한 프로젝트의 도메인은 사람이 직접
     * 연결하는 것이라 수십 개면 이미 비정상이고, 200 은 그 훨씬 위다 — 개요의 "현재 도메인" 선택
     * 로직이 최신순 목록에서 고르므로 상한에 걸려도 고르는 결과는 같다.
     */
    private static final int DEFAULT_DOMAIN_LIMIT = 200;
    private static final int MAX_DOMAIN_LIMIT = 500;

    public List<DomainBindingResult> getProjectDomains(Long ownerUserId, Long projectId) {
        return getProjectDomains(ownerUserId, projectId, null, null).items();
    }

    /** U6(#341) 6-4: 상한 + 커서. 최신순이라 {@code after} 는 "그 도메인보다 오래된 것" 을 뜻한다. */
    public CursorPage<DomainBindingResult> getProjectDomains(Long ownerUserId,
                                                            Long projectId,
                                                            Integer limit,
                                                            String after) {
        resolveProject(ownerUserId, projectId);
        int size = CursorPaging.clamp(limit, DEFAULT_DOMAIN_LIMIT, MAX_DOMAIN_LIMIT);
        List<DomainBinding> probed = domainBindingRepository.findProjectDomainsPage(
                projectId, CursorPaging.parseCursor(after), size + 1);
        return CursorPaging.slice(probed, size, domain -> String.valueOf(domain.getId()))
                .map(this::toResult);
    }

    public DomainBindingResult getDomain(Long ownerUserId, Long domainId) {
        return toResult(resolveDomainOwnedBy(domainId, ownerUserId));
    }

    public VerificationGuideResult getVerificationGuide(Long ownerUserId, Long domainId) {
        DomainBinding domain = resolveDomainOwnedBy(domainId, ownerUserId);
        if (domain.getHostingTarget() == DomainHostingTarget.AWS_S3_FRONTEND
                && domain.getType() == DomainType.CUSTOM_DOMAIN) {
            return s3CustomDomainGuide(domain);
        }
        if (domain.getDnsTarget() == null || domain.getDnsTarget().isBlank()) {
            throw new IllegalArgumentException("도메인 검증 대상이 아직 생성되지 않았습니다. domainId=" + domainId);
        }
        return new VerificationGuideResult(
                domain.getHostname(),
                domain.getVerificationMethod(),
                List.of(new VerificationRecordResult(
                        domain.getVerificationMethod().name(),
                        toRecordHost(domain.getHostname()),
                        domain.getDnsTarget()
                ))
        );
    }

    /**
     * S3 커스텀 도메인은 사용자가 자기 DNS 에 CNAME 을 두 단계로 넣는다(우리가 못 건다). 각 단계에서 지금
     * 넣어야 할 레코드를 보여준다:
     * <ol>
     *   <li>배포 전(distributionId 없음): ACM DNS 검증 CNAME — 인증서 발급용. 넣으면 인증서가 발급되고
     *       워커가 CloudFront 배포를 만든다.</li>
     *   <li>배포 후(distributionId 있음): 도메인 → CloudFront 최종 CNAME — 트래픽 라우팅용. 넣으면 https 서빙.</li>
     * </ol>
     */
    private VerificationGuideResult s3CustomDomainGuide(DomainBinding domain) {
        if (domain.getCloudfrontDistributionId() == null) {
            S3CdnProvisioningPort.AcmCertStatus cert = s3CdnProvisioningPort
                    .describeCertificate(domain.getProjectId(), domain.getAcmCertificateArn());
            if (!cert.hasValidationRecord()) {
                throw new IllegalArgumentException("인증서 검증 레코드를 준비 중입니다. 잠시 후 다시 시도해주세요.");
            }
            return new VerificationGuideResult(
                    domain.getHostname(),
                    VerificationMethod.CNAME,
                    List.of(new VerificationRecordResult(
                            "CNAME",
                            stripTrailingDot(cert.validationRecordName()),
                            stripTrailingDot(cert.validationRecordValue()))));
        }
        return new VerificationGuideResult(
                domain.getHostname(),
                VerificationMethod.CNAME,
                List.of(new VerificationRecordResult(
                        "CNAME",
                        domain.getHostname(),
                        domain.getDnsTarget())));
    }

    private String stripTrailingDot(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private Project resolveProject(Long ownerUserId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(
                        "Project not found. projectId=" + projectId + ", ownerUserId=" + ownerUserId));
    }

    private DomainBinding resolveDomainOwnedBy(Long domainId, Long ownerUserId) {
        DomainBinding domain = domainBindingRepository.findById(domainId)
                .orElseThrow(() -> new NotFoundException("도메인을 찾을 수 없습니다. domainId=" + domainId));
        resolveProject(ownerUserId, domain.getProjectId());
        return domain;
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
                domain.getUpdatedAt(),
                backendAddressPort.resolveServerId(domain.getProjectId(), domain.getHostingTarget()).orElse(null)
        );
    }

    private String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("keyword must not be blank");
        }
        String label = value.trim().toLowerCase();
        if (!label.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")) {
            throw new IllegalArgumentException("도메인 라벨은 영문 소문자, 숫자, 하이픈만 사용할 수 있습니다.");
        }
        return label;
    }

    private String toRecordHost(String hostname) {
        String[] parts = hostname.split("\\.");
        if (parts.length <= 2) {
            return "@";
        }
        return parts[0];
    }
}
