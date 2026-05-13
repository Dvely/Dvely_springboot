package com.example.dvely.domainbinding.presentation.dto.response;

import java.time.LocalDateTime;

public record DomainResponse(
        Long domainId,
        Long projectId,
        String type,
        String hostname,
        String status,
        String verificationMethod,
        String dnsTarget,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
