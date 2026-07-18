package com.example.dvely.project.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A project's monthly cost budget (design D2/D4/D6) — one row per project (project_id is the PK,
 * see V27), mirroring {@code ProjectCloudConnectionSetting}/{@code ProjectInfrastructureSetting}'s
 * shape: a plain constructor pair (new vs. restored-from-persistence) plus a mutation method
 * ({@link #update}) for upsert. Only the budget itself is persisted — the cost estimate it is
 * compared against is always recomputed on the fly by {@code InfrastructureCostEstimator} and
 * never stored here (design D2).
 */
public class ProjectBudgetSetting {

    private static final String SUPPORTED_CURRENCY = "USD";
    private static final int AMOUNT_SCALE = 2;

    private final Long projectId;
    private BigDecimal monthlyBudgetAmount;
    private String currency;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ProjectBudgetSetting(Long projectId, BigDecimal monthlyBudgetAmount, String currency) {
        this(projectId, monthlyBudgetAmount, currency, null, null);
    }

    public ProjectBudgetSetting(Long projectId,
                                BigDecimal monthlyBudgetAmount,
                                String currency,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.monthlyBudgetAmount = requireValidAmount(monthlyBudgetAmount);
        this.currency = requireSupportedCurrency(currency);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void update(BigDecimal monthlyBudgetAmount, String currency) {
        this.monthlyBudgetAmount = requireValidAmount(monthlyBudgetAmount);
        this.currency = requireSupportedCurrency(currency);
    }

    public Long getProjectId() {
        return projectId;
    }

    public BigDecimal getMonthlyBudgetAmount() {
        return monthlyBudgetAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // design D6: BigDecimal throughout, normalized to scale 2 here so every stored/compared
    // amount has a consistent scale regardless of how many fraction digits the caller supplied.
    // Bean Validation (@DecimalMin/@Digits) already rejects <=0 or >2-fraction-digit input at the
    // web boundary — this is the domain-level invariant for any caller that builds this object
    // directly (e.g. tests, or a future non-HTTP entry point).
    private static BigDecimal requireValidAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "monthlyBudgetAmount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("monthlyBudgetAmount must be greater than zero");
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    // design D4: currency is a seam for future non-USD support, but today only "USD" (or an
    // absent value, which defaults to it) is accepted. Anything else 400s with this exact message
    // so the controller layer needs no currency-specific validation branch of its own.
    private static String requireSupportedCurrency(String currency) {
        String normalized = currency == null ? SUPPORTED_CURRENCY : currency;
        if (!SUPPORTED_CURRENCY.equals(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 통화입니다: " + currency);
        }
        return normalized;
    }
}
