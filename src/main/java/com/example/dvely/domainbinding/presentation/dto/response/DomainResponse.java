package com.example.dvely.domainbinding.presentation.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DomainResponse(
        Long domainId,
        Long projectId,
        String type,
        String hostingTarget,
        String hostname,
        String status,
        String verificationMethod,
        String dnsTarget,
        boolean httpsEnforced,
        String certificateStatus,
        LocalDate certificateExpiresAt,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
