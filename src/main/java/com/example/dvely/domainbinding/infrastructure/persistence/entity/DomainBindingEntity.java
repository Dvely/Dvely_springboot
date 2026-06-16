package com.example.dvely.domainbinding.infrastructure.persistence.entity;

import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "domains")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomainBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "domain_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "domain_name", nullable = false)
    private String hostname;

    @Column(name = "domain_type", nullable = false)
    private String type;

    @Column(name = "hosting_target", nullable = false)
    private String hostingTarget;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "verification_method")
    private String verificationMethod;

    @Column(name = "dns_target")
    private String dnsTarget;

    @Column(name = "cloudflare_record_id")
    private String cloudflareRecordId;

    @Column(name = "https_enforced", nullable = false)
    private boolean httpsEnforced;

    @Column(name = "certificate_status", nullable = false)
    private String certificateStatus;

    @Column(name = "certificate_expires_at")
    private LocalDate certificateExpiresAt;

    @Column(name = "dns_verified", nullable = false)
    private boolean dnsVerified;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private DomainBindingEntity(Long projectId,
                                String hostname,
                                String type,
                                String hostingTarget,
                                String status,
                                String verificationMethod,
                                String dnsTarget,
                                String cloudflareRecordId,
                                boolean httpsEnforced,
                                String certificateStatus,
                                LocalDate certificateExpiresAt,
                                boolean dnsVerified,
                                LocalDateTime lastCheckedAt) {
        this.projectId = projectId;
        this.hostname = hostname;
        this.type = type;
        this.hostingTarget = hostingTarget;
        this.status = status;
        this.verificationMethod = verificationMethod;
        this.dnsTarget = dnsTarget;
        this.cloudflareRecordId = cloudflareRecordId;
        this.httpsEnforced = httpsEnforced;
        this.certificateStatus = certificateStatus;
        this.certificateExpiresAt = certificateExpiresAt;
        this.dnsVerified = dnsVerified;
        this.lastCheckedAt = lastCheckedAt;
    }

    public static DomainBindingEntity from(DomainBinding domain) {
        return new DomainBindingEntity(
                domain.getProjectId(),
                domain.getHostname(),
                domain.getType().name(),
                domain.getHostingTarget().name(),
                domain.getStatus().name(),
                domain.getVerificationMethod() == null ? null : domain.getVerificationMethod().name(),
                domain.getDnsTarget(),
                domain.getCloudflareRecordId(),
                domain.isHttpsEnforced(),
                domain.getCertificateStatus().name(),
                domain.getCertificateExpiresAt(),
                domain.getStatus() == DomainStatus.CONNECTED,
                domain.getLastCheckedAt()
        );
    }

    public void updateFrom(DomainBinding domain) {
        this.projectId = domain.getProjectId();
        this.hostname = domain.getHostname();
        this.type = domain.getType().name();
        this.hostingTarget = domain.getHostingTarget().name();
        this.status = domain.getStatus().name();
        this.verificationMethod = domain.getVerificationMethod() == null
                ? null
                : domain.getVerificationMethod().name();
        this.dnsTarget = domain.getDnsTarget();
        this.cloudflareRecordId = domain.getCloudflareRecordId();
        this.httpsEnforced = domain.isHttpsEnforced();
        this.certificateStatus = domain.getCertificateStatus().name();
        this.certificateExpiresAt = domain.getCertificateExpiresAt();
        this.dnsVerified = domain.getStatus() == DomainStatus.CONNECTED;
        this.lastCheckedAt = domain.getLastCheckedAt();
    }

    public DomainBinding toDomain() {
        return new DomainBinding(
                id,
                projectId,
                DomainType.valueOf(type),
                DomainHostingTarget.valueOf(hostingTarget),
                hostname,
                DomainStatus.valueOf(status),
                verificationMethod == null ? null : VerificationMethod.valueOf(verificationMethod),
                dnsTarget,
                cloudflareRecordId,
                httpsEnforced,
                CertificateStatus.valueOf(certificateStatus),
                certificateExpiresAt,
                lastCheckedAt,
                createdAt,
                updatedAt
        );
    }
}
