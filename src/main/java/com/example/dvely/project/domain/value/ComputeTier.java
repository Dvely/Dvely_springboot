package com.example.dvely.project.domain.value;

/**
 * Provider-neutral compute sizing tier (design D2) — Dvely's non-developer users pick
 * "small vs. large", not a concrete instance type. On AWS the tier is the default size of a new
 * backend server (#597): {@link AwsComputeTierTable} maps it to t3.micro/small/medium/large and is
 * also where the cost estimate reads its AWS SERVER price from.
 */
public enum ComputeTier {
    MICRO,
    SMALL,
    MEDIUM,
    LARGE
}
