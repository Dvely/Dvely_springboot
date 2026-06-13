package com.example.dvely.project.application.result;

import java.time.LocalDateTime;

public record ProjectDomainSummaryResult(
        Long domainId,
        String hostname,
        String url,
        String type,
        String status,
        LocalDateTime lastCheckedAt
) {
}
