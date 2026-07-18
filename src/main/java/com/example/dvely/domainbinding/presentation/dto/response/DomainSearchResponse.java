package com.example.dvely.domainbinding.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "도메인 검색 결과. 현재는 managed_subdomain의 실제 사용 가능 여부만 Cloudflare로 조회하고, " +
        "purchasable_domain은 후보만 제공합니다(실제 구매/등록 미지원).")
public record DomainSearchResponse(
        @Schema(description = "검색에 사용한 키워드", example = "myproject") String keyword,
        List<Result> results
) {

    @Schema(description = "검색 후보 1건")
    public record Result(
            @Schema(description = "후보 타입", allowableValues = {"managed_subdomain", "purchasable_domain"}) String type,
            @Schema(description = "전체 hostname", example = "myproject.qeploy.com") String hostname,
            @Schema(description = "사용 가능 여부") boolean available,
            @Schema(description = "가격. managed_subdomain은 무료(0)이거나 null일 수 있음", nullable = true) BigDecimal price,
            @Schema(description = "통화. 가격이 없으면 null", nullable = true) String currency
    ) {
    }
}
