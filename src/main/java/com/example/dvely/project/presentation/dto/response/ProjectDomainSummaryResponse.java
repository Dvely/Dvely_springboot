package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Overview 현재 도메인 요약")
public record ProjectDomainSummaryResponse(
        Long domainId,
        String hostname,
        String url,
        String type,
        String status,
        LocalDateTime lastCheckedAt
) {
}
