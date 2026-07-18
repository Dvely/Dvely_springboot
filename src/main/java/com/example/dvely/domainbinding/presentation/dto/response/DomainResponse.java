package com.example.dvely.domainbinding.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "도메인 연결 및 DNS 검증 상태")
public record DomainResponse(
        @Schema(description = "도메인 ID") Long domainId,
        @Schema(description = "프로젝트 ID") Long projectId,

        @Schema(description = "도메인 타입", allowableValues = {"managed_subdomain", "custom_domain", "purchasable_domain"}, example = "managed_subdomain")
        String type,

        @Schema(description = "배포 대상", allowableValues = {"GITHUB_PAGES", "AWS", "GCP"}, example = "GITHUB_PAGES")
        String hostingTarget,

        @Schema(description = "전체 hostname", example = "myproject.qeploy.com") String hostname,

        @Schema(description = "연결 상태", allowableValues = {"REQUESTED", "PROVISIONING", "VERIFYING", "CONNECTED", "FAILED"}, example = "VERIFYING")
        String status,

        @Schema(description = "DNS 검증 방식. managed_subdomain은 Cloudflare가 자동 처리하므로 null일 수 있음", allowableValues = {"CNAME", "A"}, nullable = true)
        String verificationMethod,

        @Schema(description = "사용자가 DNS에 등록해야 하는 값. GitHub Pages 대상이면 실제 배포된 GitHub Pages hostname")
        String dnsTarget,

        @Schema(description = "HTTPS 강제 여부") boolean httpsEnforced,

        @Schema(description = "TLS 인증서 발급 상태", allowableValues = {"PENDING", "PROVISIONING", "ACTIVE", "FAILED"}, example = "ACTIVE")
        String certificateStatus,

        @Schema(description = "인증서 만료일. 미발급 시 null", nullable = true) LocalDate certificateExpiresAt,
        @Schema(description = "마지막 DNS 검증 확인 시각") LocalDateTime lastCheckedAt,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 수정 시각") LocalDateTime updatedAt
) {
}
