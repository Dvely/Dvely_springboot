package com.example.dvely.project.application.result;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response shape for GET/PUT {@code .../settings/cost-budget} (design §1.1) — PUT returns the
 * same shape as GET (a full recalculation right after the write) so FE can refresh its
 * budget-warning UI from the PUT response alone. {@code assumptions}/{@code priceTableVersion}
 * describe {@code InfrastructureCostEstimator}'s static price table itself, not a per-project
 * computation, so they stay populated even when {@code costAvailable} is false — only the
 * genuinely project-dependent fields ({@code provider}, {@code estimatedMonthlyCost},
 * {@code resourceCosts}) go null/empty in that case (design D5).
 */
public record ProjectCostBudgetResult(
        Long projectId,
        boolean costAvailable,
        String provider,
        String currency,
        BigDecimal estimatedMonthlyCost,
        List<ResourceCost> resourceCosts,
        List<String> assumptions,
        String priceTableVersion,
        Budget budget,
        String budgetStatus,
        BigDecimal budgetUsagePercent
) {

    public record ResourceCost(String resourceType, String description, BigDecimal monthlyCost) {
    }

    public record Budget(BigDecimal monthlyBudgetAmount, String currency, LocalDateTime updatedAt) {
    }
}
