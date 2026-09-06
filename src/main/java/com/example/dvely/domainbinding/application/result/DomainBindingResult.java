package com.example.dvely.domainbinding.application.result;

import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DomainBindingResult(
        Long domainId,
        Long projectId,
        DomainType type,
        DomainHostingTarget hostingTarget,
        String hostname,
        DomainStatus status,
        VerificationMethod verificationMethod,
        String dnsTarget,
        boolean httpsEnforced,
        CertificateStatus certificateStatus,
        LocalDate certificateExpiresAt,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // 이 도메인이 가리키는 프로젝트의 RUNNING EC2 서버 id. EC2 대상이 아니거나 서버가 안 떠 있으면 null.
        // FE 가 도메인에서 그 서버 상세·로그로 이어간다.
        Long serverId
) {

    /** serverId 를 모르는(=EC2 서버 문맥이 없는) 호출부용 오버로드. serverId=null 로 만든다. */
    public DomainBindingResult(
            Long domainId,
            Long projectId,
            DomainType type,
            DomainHostingTarget hostingTarget,
            String hostname,
            DomainStatus status,
            VerificationMethod verificationMethod,
            String dnsTarget,
            boolean httpsEnforced,
            CertificateStatus certificateStatus,
            LocalDate certificateExpiresAt,
            LocalDateTime lastCheckedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(domainId, projectId, type, hostingTarget, hostname, status, verificationMethod, dnsTarget,
                httpsEnforced, certificateStatus, certificateExpiresAt, lastCheckedAt, createdAt, updatedAt, null);
    }
}
