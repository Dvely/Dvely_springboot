package com.example.dvely.environment.application.result;

import java.time.LocalDateTime;

/**
 * {@code value} is already masked by the time this record is built (see
 * EnvironmentVariableQueryService#toResult) — {@code secret ? null : plaintext}. Controllers and
 * the facade never see the plaintext for a secret variable; there is no way to accidentally
 * expose it downstream of this type.
 */
public record EnvironmentVariableResult(
        Long environmentVariableId,
        String scope,
        String key,
        String value,
        boolean secret,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
