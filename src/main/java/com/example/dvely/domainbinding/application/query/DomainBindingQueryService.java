package com.example.dvely.domainbinding.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchCandidateResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.application.result.VerificationRecordResult;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
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

    public List<DomainBindingResult> getProjectDomains(Long ownerUserId, Long projectId) {
        resolveProject(ownerUserId, projectId);
        return domainBindingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toResult)
                .toList();
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
                domain.getUpdatedAt()
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
