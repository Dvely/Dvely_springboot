package com.example.dvely.aiaccount.domain.model;

import com.example.dvely.agent.domain.value.AiProvider;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A user's own API key for one AI vendor (BYOK — bring your own key).
 *
 * <p>BYOK is the only sanctioned way to let a hosted product run AI work on an end user's account:
 * routing subscription credentials (Claude Pro/Max, ChatGPT Plus) through third-party
 * infrastructure is explicitly prohibited by the providers, and operator-pooled keys are barred by
 * the same "no paying for, reselling, or intermediating usage on behalf of end users" clause.
 * Hence {@code userId} is mandatory and the storage-level unique key is (user, provider) — see
 * {@code docs/byok-coding-agent-design.md}.</p>
 *
 * <p><b>Security note:</b> as with {@code EnvironmentVariable}, there is deliberately no
 * {@code toString()} override — its absence keeps {@code apiKey} out of any accidental
 * {@code log.info("{}", credential)} call. Exception messages here never include the key either
 * (only provider, which is safe to log). Encryption happens at the persistence boundary (JPA
 * {@code @Convert(AesEncryptor.class)}); in memory the key is always plaintext.</p>
 *
 * <p>{@code provider} is a <b>vendor</b>, not an execution mode. Claude Code runs on the
 * {@code ANTHROPIC} key and Codex on the {@code OPENAI} key, so splitting rows per execution mode
 * would force a user to paste the same key twice.</p>
 */
public class AiProviderCredential {

    /**
     * Generous enough for every current vendor format (Anthropic {@code sk-ant-…} keys are ~108
     * chars, OpenAI project keys ~164) with headroom, while still bounding what can be stored.
     */
    private static final int MAX_API_KEY_LENGTH = 512;
    private static final int MAX_LABEL_LENGTH = 64;

    /** Characters kept visible by {@link #maskedApiKey()} — enough to tell key formats apart. */
    private static final int MASK_PREFIX_LENGTH = 6;

    private final Long id;
    private final Long userId;
    private final AiProvider provider;
    private String apiKey;
    private String label;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** New-credential constructor. */
    public AiProviderCredential(Long userId, AiProvider provider, String apiKey, String label) {
        this(null, userId, provider, apiKey, label, null, null);
    }

    /** Restore-from-storage constructor. */
    public AiProviderCredential(Long id,
                                Long userId,
                                AiProvider provider,
                                String apiKey,
                                String label,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.provider = validateProvider(provider);
        this.apiKey = validateApiKey(apiKey);
        this.label = validateLabel(label);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Key rotation. The provider and owner never change — that would be a different credential. */
    public void changeApiKey(String newApiKey) {
        this.apiKey = validateApiKey(newApiKey);
    }

    public void changeLabel(String newLabel) {
        this.label = validateLabel(newLabel);
    }

    /**
     * What callers are allowed to show. Only a short leading slice survives — enough for a user to
     * recognise which key they registered ({@code sk-ant}, {@code sk-pro}) without handing back
     * material that shortens a brute-force. Trailing characters are deliberately <b>not</b> shown:
     * a prefix is near-constant per vendor and leaks almost nothing, whereas a suffix is real key
     * entropy.
     */
    public String maskedApiKey() {
        if (apiKey.length() <= MASK_PREFIX_LENGTH) {
            return "****";
        }
        return apiKey.substring(0, MASK_PREFIX_LENGTH) + "****";
    }

    /**
     * Only a vendor may own a credential. {@code CLAUDE_CODE}/{@code CODEX} are execution modes
     * that read a vendor's key ({@link AiProvider#credentialVendor()}), so storing a row under one
     * of them would give the same key two homes and let the two copies drift out of step. Callers
     * holding an execution mode must convert first.
     */
    private static AiProvider validateProvider(AiProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        if (provider.isCodingAgent()) {
            throw new IllegalArgumentException(
                    "크리덴셜은 벤더 단위로만 저장합니다. " + provider + " 대신 "
                            + provider.credentialVendor() + " 로 등록해주세요.");
        }
        return provider;
    }

    private static String validateApiKey(String rawApiKey) {
        String trimmed = rawApiKey == null ? null : rawApiKey.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException("apiKey는 비어 있을 수 없습니다.");
        }
        if (trimmed.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("apiKey는 최대 " + MAX_API_KEY_LENGTH + "자까지 허용됩니다.");
        }
        // No vendor key contains whitespace or control characters, and this value is injected into
        // a container's environment verbatim — rejecting them here keeps a pasted-with-a-newline
        // key from silently becoming a broken (or shell-surprising) env var downstream.
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("apiKey에 공백 또는 제어 문자를 포함할 수 없습니다.");
            }
        }
        return trimmed;
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
    public AiProvider getProvider() { return provider; }
    public String getApiKey() { return apiKey; }
    public String getLabel() { return label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
