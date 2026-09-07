package com.example.dvely.apitoken.application.result;

import java.time.LocalDateTime;

/**
 * A token as listed. There is no plaintext field — issuance returns {@link IssuedApiTokenResult}
 * instead, so nothing downstream of a list query has a token to leak.
 */
public record ApiTokenResult(
        Long apiTokenId,
        String tokenPrefix,
        String scope,
        String label,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt
) {
}
