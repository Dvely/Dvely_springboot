package com.example.dvely.environment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code value} is {@code @NotNull} but NOT {@code @NotBlank} — an empty string is a valid
 * environment variable value (see EnvironmentVariable's value rules). Full format validation
 * (key pattern, 4096-char/NUL checks) happens in the domain constructor; these annotations are
 * only the first-line defense per the domain's contract-first convention.
 */
public record CreateEnvironmentVariableRequest(
        @NotBlank @Size(max = 128) String key,
        @NotNull @Size(max = 4096) String value,
        @NotBlank String scope,
        boolean secret
) {
}
