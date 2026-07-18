package com.example.dvely.project.domain.value;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of one {@code InfrastructureCostEstimator.estimate(...)} call — a snapshot, never
 * persisted (design D2: the price table is a code constant, so recomputation is effectively free
 * and always reflects the project's current infra configuration). {@code totalMonthlyCost} is
 * always the sum of {@code resourceCosts}' amounts, computed once by the estimator rather than
 * re-derived by each caller, so the sum-equals-total invariant can't drift between call sites.
 */
public record CostEstimate(
        BigDecimal totalMonthlyCost,
        List<ResourceCost> resourceCosts,
        List<String> assumptions,
        String priceTableVersion
) {

    public record ResourceCost(CostResourceType resourceType, String description, BigDecimal monthlyCost) {
    }
}
