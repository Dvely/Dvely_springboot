package com.example.dvely.apitoken.domain.service;

import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.model.IssuedApiToken;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/** Mints tokens and hashes presented ones. The only place either operation happens. */
public final class ApiTokenGenerator {

    /**
     * Marks a credential as a Qeploy PAT so the auth filter can route it without trying to parse it
     * as a JWT first. A wrong guess there would turn every bad token into a stack trace.
     */
    public static final String PREFIX = "qp_";

    /** 256 bits. Enough that guessing is not a threat model, which is why SHA-256 suffices below. */
    private static final int SECRET_BYTES = 32;

    /** How much of the token is stored in the clear so a user can recognise it in a list. */
    private static final int DISPLAY_PREFIX_LENGTH = PREFIX.length() + 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokenGenerator() {
    }

    public static IssuedApiToken issue(Long userId,
                                       ApiTokenScope scope,
                                       String label,
                                       LocalDateTime expiresAt) {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        ApiToken stored = new ApiToken(
                userId,
                hash(plaintext),
                plaintext.substring(0, DISPLAY_PREFIX_LENGTH),
                scope,
                label,
                expiresAt);
        return new IssuedApiToken(stored, plaintext);
    }

    /**
     * SHA-256, not bcrypt/argon2. Those exist to make guessing a <i>low-entropy</i> secret slow;
     * this one is 256 random bits, so no work factor changes whether it can be guessed. What a work
     * factor would change is the cost of every authenticated request, since this runs on each one.
     */
    public static String hash(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    public static boolean looksLikeApiToken(String candidate) {
        return candidate != null && candidate.startsWith(PREFIX);
    }
}
