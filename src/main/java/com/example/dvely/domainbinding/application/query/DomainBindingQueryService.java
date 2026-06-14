package com.example.dvely.domainbinding.application.query;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchCandidateResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.application.result.VerificationRecordResult;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainType;
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
