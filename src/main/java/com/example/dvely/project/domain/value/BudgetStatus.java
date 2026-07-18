package com.example.dvely.project.domain.value;

/**
 * Budget-vs-estimate derived state (design D3) — never persisted; recomputed from the current
 * cost estimate and the stored budget row on every GET/PUT. {@code NOT_EVALUABLE} covers "a
 * budget is set but no estimate can be produced" (no infra configured / no CONNECTED cloud
 * connection selected), which is distinct from {@code NO_BUDGET} (nothing set at all).
 */
public enum BudgetStatus {
    NO_BUDGET,
    WITHIN_BUDGET,
    OVER_BUDGET,
    NOT_EVALUABLE
}
