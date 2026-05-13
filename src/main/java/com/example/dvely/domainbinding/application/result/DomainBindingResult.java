package com.example.dvely.domainbinding.application.result;

import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDateTime;

public record DomainBindingResult(
        Long domainId,
        Long projectId,
        DomainType type,
        String hostname,
        DomainStatus status,
        VerificationMethod verificationMethod,
        String dnsTarget,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
