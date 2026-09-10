package com.example.dvely.apitoken.domain.model;

import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A personal access token, as stored — a hash and some metadata, never the token itself.
 *
 * <p>The plaintext exists only inside {@link IssuedApiToken}, which is returned once from issuance
 * and never persisted. That asymmetry is the whole security model: losing the database does not
 * hand anyone a working token, and no code path can accidentally re-display one because the value
 * simply is not there to display.</p>
 *
 * <p>Contrast with {@code AiProviderCredential}, which encrypts rather than hashes: that key has to
 * be handed to a vendor CLI later, so it must be recoverable. A PAT only ever needs to be
 * <i>compared</i>, so it is hashed and the original discarded.</p>
 */
public class ApiToken {

    private static final int MAX_LABEL_LENGTH = 64;

    private final Long id;
    private final Long userId;
    private final String tokenHash;
    private final String tokenPrefix;
    private final ApiTokenScope scope;
    private final String label;
    private final LocalDateTime expiresAt;
    private final LocalDateTime lastUsedAt;
    private final LocalDateTime createdAt;

    /** New-token constructor. */
    public ApiToken(Long userId,
                    String tokenHash,
                    String tokenPrefix,
                    ApiTokenScope scope,
                    String label,
                    LocalDateTime expiresAt) {
        this(null, userId, tokenHash, tokenPrefix, scope, label, expiresAt, null, null);
    }

    /** Restore-from-storage constructor. */
    public ApiToken(Long id,
                    Long userId,
                    String tokenHash,
                    String tokenPrefix,
                    ApiTokenScope scope,
                    String label,
                    LocalDateTime expiresAt,
                    LocalDateTime lastUsedAt,
                    LocalDateTime createdAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.tokenPrefix = Objects.requireNonNull(tokenPrefix, "tokenPrefix must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.label = validateLabel(label);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
    }

    /**
     * @param now the caller's clock, passed in rather than read here so expiry is testable without
     *            waiting and without a static clock dependency
     */
    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean allowsWrite() {
        return scope.allowsWrite();
    }

    /**
     * Whether {@code lastUsedAt} is stale enough to be worth a write. Every authenticated request
     * would otherwise issue an UPDATE purely to move a timestamp; the field exists so a user can
     * spot a token they no longer recognise, and hour granularity answers that just as well.
     */
    public boolean shouldRefreshLastUsed(LocalDateTime now) {
        return lastUsedAt == null || lastUsedAt.isBefore(now.minusHours(1));
    }

    private static String validateLabel(String rawLabel) {
        if (rawLabel == null) {
            return null;
        }
        String trimmed = rawLabel.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("label은 최대 " + MAX_LABEL_LENGTH + "자까지 허용됩니다.");
        }
        return trimmed;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public ApiTokenScope getScope() { return scope; }
    public String getLabel() { return label; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
