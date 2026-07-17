package com.example.dvely.environment.domain.model;

import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A single project-scoped environment variable (or secret). Encryption of {@code value} happens
 * at the persistence boundary (JPA {@code @Convert(AesEncryptor.class)}) — in memory it is always
 * plaintext, which is exactly why this class must never leak it through logging.
 *
 * <p><b>Security note (see U3 design doc §4):</b> do not add a {@code toString()} override here.
 * The absence of one is deliberate — it keeps {@code value} out of any accidental
 * {@code log.info("{}", variable)}-style call. Exception messages thrown by this class also never
 * include {@code value} (only key/scope, which are safe to log).</p>
 *
 * <p>{@code key} and {@code scope} are immutable after construction (U3 design D6): renaming a
 * variable means delete + recreate. {@code secret} may only be promoted false→true, never
 * reversed (D7) — see {@link #unmarkSecret()}.</p>
 */
public class EnvironmentVariable {

    // POSIX-style environment variable name: starts with a letter or underscore, followed by up
    // to 127 more letters/digits/underscores (128 chars total). Case-sensitive by design (D9) —
    // callers must not upper-case or otherwise normalize the key beyond trimming.
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    private static final int MAX_VALUE_LENGTH = 4096;

    private final Long id;
    private final Long projectId;
    private final EnvironmentScope scope;
    private final String key;
    private String value;
    private boolean secret;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** New-variable constructor. */
    public EnvironmentVariable(Long projectId, EnvironmentScope scope, String key, String value, boolean secret) {
        this(null, projectId, scope, key, value, secret, null, null);
    }

    /** Restore-from-storage constructor. */
    public EnvironmentVariable(Long id,
                              Long projectId,
                              EnvironmentScope scope,
                              String key,
                              String value,
                              boolean secret,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.key = validateKey(key);
        this.value = validateValue(value);
        this.secret = secret;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Replaces the value (used by PATCH). Value rules are re-checked; key/scope are untouched. */
    public void changeValue(String newValue) {
        this.value = validateValue(newValue);
    }

    /** false → true promotion. Also safe to call when already true (idempotent no-op). */
    public void markSecret() {
        this.secret = true;
    }

    /**
     * true → false is never allowed (D7): once a value has been hidden from API responses,
     * un-hiding it would let a caller silently expose something the owner deliberately protected
     * (downgrade attack / accidental exposure). Callers should only invoke this when
     * {@code isSecret()} is already true and the request is asking to flip it false — the
     * resulting exception becomes the 400 response. If the request is false and the variable is
     * already non-secret, callers must simply not invoke this (no-op), since that is not a
     * downgrade at all.
     */
    public void unmarkSecret() {
        throw new IllegalArgumentException("secret 해제는 허용되지 않습니다. 삭제 후 다시 생성해주세요.");
    }

    private static String validateKey(String rawKey) {
        String trimmed = rawKey == null ? null : rawKey.trim();
        if (trimmed == null || !KEY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "key는 영문/숫자/밑줄(_)로 구성되고 숫자로 시작할 수 없으며 최대 128자여야 합니다."
            );
        }
        return trimmed;
    }

    private static String validateValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value는 null일 수 없습니다.");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("value는 최대 " + MAX_VALUE_LENGTH + "자까지 허용됩니다.");
        }
        // Whitespace inside a value can be meaningful (e.g. multi-line PEM keys) so it is never
        // trimmed — only NUL is rejected, since it cannot round-trip through typical env
        // injection mechanisms (docker --env, shell export) and likely indicates bad input.
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("value에 NUL 문자를 포함할 수 없습니다.");
        }
        return value;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public EnvironmentScope getScope() { return scope; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public boolean isSecret() { return secret; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
