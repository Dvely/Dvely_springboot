package com.example.dvely.project.application.result;

import java.time.LocalDateTime;
import java.time.LocalDate;

public record ProjectDomainSummaryResult(
        Long domainId,
        String hostname,
        String url,
        String type,
        String hostingTarget,
        String status,
        boolean httpsEnforced,
        String certificateStatus,
        LocalDate certificateExpiresAt,
        LocalDateTime lastCheckedAt
) {
}
