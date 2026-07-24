package com.example.dvely.audit.domain.value;

/**
 * Fixed catalog of auditable actions (design §3.1, BI-189~192 — 16 constants, this unit's full
 * scope). Each constant carries its own {@link AuditCategory} so call sites only ever pass an
 * {@code AuditAction} — there is no separate "category" parameter that could drift out of sync
 * with the action (design §2.3: "action -> category 파생, 불일치 원천 차단").
 *
 * <p>This catalog is a closed boundary (design ADR-A5/§3.2): actions not listed here (read-only
 * operations, non-terminal retries, installation events, environment-variable changes, ...) are
 * deliberately never recorded. Extending it is a design-level decision (a "카탈로그 개정"), not a
 * call-site judgment call — see the design doc's exclusion table for the reasoning per omitted
 * case.</p>
 */
public enum AuditAction {

    // ── GITHUB (BI-189) ──────────────────────────────────────────────────────
    REPOSITORY_CREATED(AuditCategory.GITHUB),
    REPOSITORY_CONNECTED(AuditCategory.GITHUB),
    REPOSITORY_DISCONNECTED(AuditCategory.GITHUB),
    REPOSITORY_DELETED(AuditCategory.GITHUB),
    PREVIEW_BRANCH_PUSHED(AuditCategory.GITHUB),
    RESULT_MERGED(AuditCategory.GITHUB),

    // ── DEPLOYMENT (BI-190) ──────────────────────────────────────────────────
    DEPLOYMENT_REQUESTED(AuditCategory.DEPLOYMENT),
    DEPLOYMENT_RETRY_REQUESTED(AuditCategory.DEPLOYMENT),
    DEPLOYMENT_SUCCEEDED(AuditCategory.DEPLOYMENT),
    DEPLOYMENT_FAILED(AuditCategory.DEPLOYMENT),

    // ── DOMAIN (BI-191) ──────────────────────────────────────────────────────
    DOMAIN_BOUND(AuditCategory.DOMAIN),
    DOMAIN_DELETED(AuditCategory.DOMAIN),

    // ── INFRA (BI-192) ───────────────────────────────────────────────────────
    PREVIEW_RESTARTED(AuditCategory.INFRA),
    INFRA_CONFIG_CHANGE_REQUESTED(AuditCategory.INFRA),
    INFRA_CONFIG_APPLIED(AuditCategory.INFRA),
    INFRA_CONFIG_REJECTED(AuditCategory.INFRA);

    private final AuditCategory category;

    AuditAction(AuditCategory category) {
        this.category = category;
    }

    public AuditCategory category() {
        return category;
    }
}
