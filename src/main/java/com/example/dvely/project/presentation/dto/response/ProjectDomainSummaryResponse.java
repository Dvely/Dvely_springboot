package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Overview 현재 도메인 요약")
public record ProjectDomainSummaryResponse(
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
