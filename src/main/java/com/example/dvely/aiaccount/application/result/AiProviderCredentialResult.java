package com.example.dvely.aiaccount.application.result;

import java.time.LocalDateTime;

/**
 * A registered credential as callers are allowed to see it.
 *
 * <p>There is deliberately no plaintext field: {@code maskedApiKey} is already masked by the time
 * this record is built (see {@code AiProviderCredentialQueryService#toResult}), so no controller,
 * facade, or serializer downstream of this type has a plaintext key to leak even by accident. The
 * only code that ever holds the real key is the execution path that injects it into a container's
 * environment.</p>
 */
public record AiProviderCredentialResult(
        Long aiProviderCredentialId,
        String provider,
        String maskedApiKey,
        String label,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
