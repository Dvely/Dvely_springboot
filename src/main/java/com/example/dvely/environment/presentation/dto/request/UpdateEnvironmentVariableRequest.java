package com.example.dvely.environment.presentation.dto.request;

import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: {@code value == null} means "keep the current value" (send {@code ""}
 * explicitly to set an empty value); {@code secret == null} means "keep the current flag".
 * {@code key}/{@code scope} have no fields here at all — they are immutable after creation.
 */
public record UpdateEnvironmentVariableRequest(
        @Size(max = 4096) String value,
        Boolean secret
) {
}
