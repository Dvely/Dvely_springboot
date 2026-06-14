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
        LocalDateTime updatedAt
) {
}
