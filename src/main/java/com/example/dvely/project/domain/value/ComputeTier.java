package com.example.dvely.project.domain.value;

/**
 * Provider-neutral compute sizing tier (design D2) — Dvely's non-developer users pick
 * "small vs. large", not a concrete instance type. The tier-to-instance mapping
 * (e.g. AWS t3.micro/small/medium/large ~ GCP e2-micro/small/medium/standard-2) is decided
 * later by EPIC 15 Cloud-Ops when actual provisioning is implemented; this unit only stores
 * the desired tier.
 */
public enum ComputeTier {
    MICRO,
    SMALL,
    MEDIUM,
    LARGE
}
