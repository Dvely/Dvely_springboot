package com.example.dvely.project.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "프로젝트 월 예산 설정 요청 (upsert, 멱등)")
public record UpdateProjectBudgetRequest(
        @Schema(description = "월 예산 금액. 0보다 커야 하고 소수점 둘째 자리까지 허용", example = "50.00")
        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 10, fraction = 2)
        BigDecimal monthlyBudgetAmount,

        @Schema(description = "통화. null 또는 'USD'만 허용 — 그 외 값은 400", example = "USD")
        String currency
) {
}
