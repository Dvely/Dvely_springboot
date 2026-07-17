package com.example.dvely.project.domain.value;

/**
 * Lifecycle of a {@code ProjectInfrastructureSettingChange} row. PENDING_APPROVAL is also the
 * "currently in flight" marker used by the single-PENDING-per-project guard (design D8) — a row
 * transitions to exactly one of APPLIED/REJECTED and never moves again (see
 * {@code ProjectInfrastructureSettingChange#decide}).
 */
public enum InfrastructureChangeStatus {
    APPLIED,
    PENDING_APPROVAL,
    REJECTED
}
