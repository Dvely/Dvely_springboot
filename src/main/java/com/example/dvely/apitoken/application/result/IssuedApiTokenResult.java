package com.example.dvely.apitoken.application.result;

/**
 * Issuance only. Carries the one and only look at the plaintext.
 *
 * <p>{@code toString()} is overridden because a record's generated one would print the token into
 * any log line that formats it.</p>
 */
public record IssuedApiTokenResult(ApiTokenResult token, String plaintext) {

    @Override
    public String toString() {
        return "IssuedApiTokenResult[token=%s, plaintext=***]".formatted(token.tokenPrefix());
    }
}
