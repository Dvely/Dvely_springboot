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
    INFRA,
    // 카탈로그 개정(2026-11-09, #613): 프로젝트가 아니라 계정에 일어난 일. project_id 가 비어 있어 프로젝트 감사 화면에는
    // 나오지 않는다.
    ACCOUNT
}
