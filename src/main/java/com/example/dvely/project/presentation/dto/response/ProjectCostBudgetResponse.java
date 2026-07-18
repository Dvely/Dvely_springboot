package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "프로젝트 비용 추정/예산 조회·저장 응답 (GET·PUT 공통 shape). "
        + "estimatedMonthlyCost 등 비용 필드는 정적 가격표 기반의 가정 추정치이며 실시간 클라우드 요금이 아닙니다.")
public record ProjectCostBudgetResponse(
        @Schema(description = "프로젝트 ID", example = "1")
        Long projectId,

        @Schema(description = "인프라 구성이 존재하고 CONNECTED 클라우드 연결이 선택되어 비용 추정이 가능한지 여부", example = "true")
        boolean costAvailable,

        @Schema(description = "추정에 사용된 클라우드 provider. 비가용 시 null", allowableValues = {"AWS", "GCP"}, example = "AWS")
        String provider,

        @Schema(description = "통화 (USD 고정)", example = "USD")
        String currency,

        @Schema(description = "월 예상 비용 합계(resourceCosts 합계와 항상 일치). 비가용 시 null", example = "26.15")
        BigDecimal estimatedMonthlyCost,

        @Schema(description = "리소스별 비용 내역. 비가용 시 빈 배열")
        List<ResourceCostResponse> resourceCosts,

        @Schema(description = "추정에 사용된 사용량 가정 문구")
        List<String> assumptions,

        @Schema(description = "정적 가격표 버전", example = "2026-07.static.1")
        String priceTableVersion,

        @Schema(description = "설정된 예산. 미설정 시 null")
        BudgetResponse budget,

        @Schema(description = "예산 대비 상태", allowableValues = {"NO_BUDGET", "WITHIN_BUDGET", "OVER_BUDGET", "NOT_EVALUABLE"}, example = "WITHIN_BUDGET")
        String budgetStatus,

        @Schema(description = "예산 대비 사용률(%), 소수 첫째 자리. 산출 불가 시 null", example = "52.3")
        BigDecimal budgetUsagePercent
) {

    @Schema(description = "리소스별 월 비용")
    public record ResourceCostResponse(
            @Schema(description = "리소스 종류", allowableValues = {"COMPUTE", "STORAGE", "NETWORK"}, example = "COMPUTE")
            String resourceType,

            @Schema(description = "설명", example = "SERVER · SMALL (AWS)")
            String description,

            @Schema(description = "월 비용", example = "17.00")
            BigDecimal monthlyCost
    ) {
    }

    @Schema(description = "설정된 월 예산")
    public record BudgetResponse(
            @Schema(description = "월 예산 금액", example = "50.00")
            BigDecimal monthlyBudgetAmount,

            @Schema(description = "통화", example = "USD")
            String currency,

            @Schema(description = "예산이 마지막으로 저장된 시각")
            LocalDateTime updatedAt
    ) {
    }
}
