package com.example.dvely.apitoken.domain.model;

/**
 * The one moment a token's plaintext exists: issuance.
 *
 * <p>Its own type so the plaintext cannot travel by accident. Nothing persists this, and the only
 * caller that ever sees {@link #plaintext()} is the issuance response — every other read path
 * works from {@link ApiToken}, which has no such field.</p>
 *
 * <p>{@code toString()} is overridden because this is a record: the generated one would print the
 * token into any log line that formats it. The accompanying test pins that.</p>
 */
public record IssuedApiToken(ApiToken stored, String plaintext) {

    @Override
    public String toString() {
        return "IssuedApiToken[prefix=%s, plaintext=***]".formatted(stored.getTokenPrefix());
    }
}
