package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Overview 현재 우선 도메인 요약. 연결된 도메인이 없으면 이 객체 자체가 null입니다.")
public record ProjectDomainSummaryResponse(
        @Schema(description = "도메인 ID") Long domainId,
        @Schema(description = "전체 hostname") String hostname,
        @Schema(description = "https://hostname 형식의 접속 URL") String url,
        @Schema(description = "도메인 타입", allowableValues = {"managed_subdomain", "custom_domain", "purchasable_domain"}) String type,
        @Schema(description = "배포 대상", allowableValues = {"GITHUB_PAGES", "AWS", "GCP"}) String hostingTarget,
        @Schema(description = "연결 상태", allowableValues = {"REQUESTED", "PROVISIONING", "VERIFYING", "CONNECTED", "FAILED"}) String status,
        @Schema(description = "HTTPS 강제 여부") boolean httpsEnforced,
        @Schema(description = "TLS 인증서 발급 상태", allowableValues = {"PENDING", "PROVISIONING", "ACTIVE", "FAILED"}) String certificateStatus,
        @Schema(description = "인증서 만료일. 미발급 시 null", nullable = true) LocalDate certificateExpiresAt,
        @Schema(description = "마지막 DNS 검증 확인 시각") LocalDateTime lastCheckedAt
) {
}
