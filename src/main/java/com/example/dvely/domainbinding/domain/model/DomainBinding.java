package com.example.dvely.domainbinding.domain.model;

import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public class DomainBinding {

    private final Long id;
    private final Long projectId;
    private final DomainType type;
    private final DomainHostingTarget hostingTarget;
    private final String hostname;
    private DomainStatus status;
    private VerificationMethod verificationMethod;
    private String dnsTarget;
    private String cloudflareRecordId;
    private boolean httpsEnforced;
    private CertificateStatus certificateStatus;
    private LocalDate certificateExpiresAt;
    private LocalDateTime lastCheckedAt;
    // AWS_S3_FRONTEND(S3 프론트 HTTPS) 프로비저닝 자원 식별자. 생성자 밖 mutable — 비동기 워커가
    // 단계별로 채운다(cert 요청 → 검증 레코드 → CloudFront 배포). 다른 호스팅 타깃에선 전부 null.
    private String acmCertificateArn;
    private String acmValidationRecordId;
    private String cloudfrontDistributionId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public DomainBinding(Long projectId,
                         DomainType type,
                         String hostname,
                         DomainStatus status,
                         VerificationMethod verificationMethod,
                         String dnsTarget) {
        this(
                null,
                projectId,
                type,
                DomainHostingTarget.GITHUB_PAGES,
                hostname,
                status,
                verificationMethod,
                dnsTarget,
                null,
                false,
                CertificateStatus.PENDING,
                null,
                null,
                null,
                null
        );
    }

    public DomainBinding(Long projectId,
                         DomainType type,
                         DomainHostingTarget hostingTarget,
                         String hostname,
                         DomainStatus status,
                         VerificationMethod verificationMethod,
                         String dnsTarget) {
        this(
                null,
                projectId,
                type,
                hostingTarget,
                hostname,
                status,
                verificationMethod,
                dnsTarget,
                null,
                false,
                CertificateStatus.PENDING,
                null,
                null,
                null,
                null
        );
    }

    public DomainBinding(Long id,
                         Long projectId,
                         DomainType type,
                         DomainHostingTarget hostingTarget,
                         String hostname,
                         DomainStatus status,
                         VerificationMethod verificationMethod,
                         String dnsTarget,
                         String cloudflareRecordId,
                         boolean httpsEnforced,
                         CertificateStatus certificateStatus,
                         LocalDate certificateExpiresAt,
                         LocalDateTime lastCheckedAt,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.hostingTarget = Objects.requireNonNull(hostingTarget, "hostingTarget must not be null");
        this.hostname = requireText(hostname, "hostname").toLowerCase();
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.verificationMethod = verificationMethod;
        this.dnsTarget = dnsTarget;
        this.cloudflareRecordId = cloudflareRecordId;
        this.httpsEnforced = httpsEnforced;
        this.certificateStatus = Objects.requireNonNull(certificateStatus, "certificateStatus must not be null");
        this.certificateExpiresAt = certificateExpiresAt;
        this.lastCheckedAt = lastCheckedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void assignCloudflareRecord(String recordId) {
        this.cloudflareRecordId = requireText(recordId, "recordId");
        this.status = DomainStatus.VERIFYING;
    }

    // ── AWS_S3_FRONTEND(CloudFront+ACM) 프로비저닝 단계별 상태 전이 ──────────────────
    // 이 경로는 상태를 PROVISIONING 으로 유지하다가, 실제 https 가 뜨면 markVerificationChecked 로
    // CONNECTED 가 된다(assignCloudflareRecord 처럼 VERIFYING 으로 넘기지 않는다 — CloudFront 배포가
    // Deployed 될 때까지 계속 프로비저닝 중이기 때문).

    /** ACM 인증서 발급 요청 직후. certArn 을 기록하고 PROVISIONING 으로 둔다. */
    public void assignAcmCertificate(String certificateArn) {
        this.acmCertificateArn = requireText(certificateArn, "certificateArn");
    }

    /** ACM DNS 검증 CNAME 을 Cloudflare 에 넣은 뒤, 그 레코드 id 를 기록(정리 때 지우기 위해). */
    public void assignAcmValidationRecord(String recordId) {
        this.acmValidationRecordId = requireText(recordId, "recordId");
    }

    /**
     * CloudFront 배포 생성 직후. 배포 id 를 기록하고 dnsTarget 을 CloudFront 도메인으로 잡는다. 상태는
     * PROVISIONING 유지(배포 Deployed·https 확인은 이후).
     *
     * @param finalCnameRecordId 관리형 서브도메인은 우리 존에 만든 최종 CNAME 의 Cloudflare 레코드 id.
     *                           커스텀 도메인은 사용자가 자기 존에 CNAME 을 넣으므로 {@code null}(우리가
     *                           지울 레코드가 없다 — dnsTarget 은 가이드로 사용자에게 안내한다).
     */
    public void assignCloudfrontDistribution(String distributionId,
                                             String cloudfrontDomain,
                                             String finalCnameRecordId) {
        this.cloudfrontDistributionId = requireText(distributionId, "distributionId");
        this.dnsTarget = requireText(cloudfrontDomain, "cloudfrontDomain");
        this.cloudflareRecordId = finalCnameRecordId;
    }

    /** DB 에서 복원할 때 생성자 밖 CDN 자원 id 를 되살린다. */
    public void restoreCdnResources(String certificateArn,
                                    String validationRecordId,
                                    String distributionId) {
        this.acmCertificateArn = certificateArn;
        this.acmValidationRecordId = validationRecordId;
        this.cloudfrontDistributionId = distributionId;
    }

    public void markVerificationChecked(boolean connected,
                                        boolean httpsEnforced,
                                        CertificateStatus certificateStatus,
                                        LocalDate certificateExpiresAt) {
        this.lastCheckedAt = LocalDateTime.now();
        this.status = connected ? DomainStatus.CONNECTED : DomainStatus.VERIFYING;
        this.httpsEnforced = httpsEnforced;
        this.certificateStatus = Objects.requireNonNull(certificateStatus);
        this.certificateExpiresAt = certificateExpiresAt;
    }

    public void fail() {
        this.status = DomainStatus.FAILED;
        this.lastCheckedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public DomainType getType() {
        return type;
    }

    public DomainHostingTarget getHostingTarget() {
        return hostingTarget;
    }

    public String getHostname() {
        return hostname;
    }

    public DomainStatus getStatus() {
        return status;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public String getDnsTarget() {
        return dnsTarget;
    }

    public String getCloudflareRecordId() {
        return cloudflareRecordId;
    }

    public boolean isHttpsEnforced() {
        return httpsEnforced;
    }

    public CertificateStatus getCertificateStatus() {
        return certificateStatus;
    }

    public LocalDate getCertificateExpiresAt() {
        return certificateExpiresAt;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public String getAcmCertificateArn() {
        return acmCertificateArn;
    }

    public String getAcmValidationRecordId() {
        return acmValidationRecordId;
    }

    public String getCloudfrontDistributionId() {
        return cloudfrontDistributionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
