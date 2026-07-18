package com.example.dvely.domainbinding.presentation.dto.request;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "도메인 연결 요청. type에 따라 label(managed_subdomain) 또는 hostname(custom_domain)만 채웁니다. " +
        "실제 연결은 Agent task로 비동기 처리되므로 응답은 domainId가 아니라 taskId입니다.")
public record BindDomainRequest(
        @Schema(description = "도메인 타입. managed_subdomain: Qeploy 관리형 서브도메인, custom_domain: 사용자 보유 도메인, " +
                "purchasable_domain: 검색 후보만 제공(구매/연결 미지원)", allowableValues = {"managed_subdomain", "custom_domain", "purchasable_domain"}, example = "managed_subdomain")
        @NotNull DomainType type,

        @Schema(description = "type=managed_subdomain일 때 사용할 서브도메인 라벨. 최종 hostname은 {label}.qeploy.com", example = "myproject")
        String label,

        @Schema(description = "type=custom_domain일 때 연결할 전체 hostname", example = "www.example.com")
        String hostname,

        @Schema(description = "DNS 검증 방식. type=custom_domain에서 사용, 생략 시 CNAME", allowableValues = {"CNAME", "A"}, example = "CNAME")
        VerificationMethod verificationMethod,

        @Schema(description = "배포 대상. 생략 시 GITHUB_PAGES", allowableValues = {"GITHUB_PAGES", "AWS", "GCP"}, example = "GITHUB_PAGES")
        DomainHostingTarget hostingTarget
) {
}
