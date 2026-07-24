package com.example.dvely.audit.domain.value;

/**
 * Top-level grouping for {@link AuditAction} (design ad-audit-log-design.md §2.1/§3.1). Stored as
 * a plain {@code VARCHAR} column (never {@code @Enumerated}, matching the approval/cloudconnection
 * precedent) so a column reorder can never silently change meaning, and so the value is directly
 * human-readable in ad-hoc SQL against {@code audit_logs}.
 */
public enum AuditCategory {
    GITHUB,
    DEPLOYMENT,
    DOMAIN,
    INFRA
}
