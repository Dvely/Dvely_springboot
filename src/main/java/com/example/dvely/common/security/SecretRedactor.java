package com.example.dvely.common.security;

import java.util.regex.Pattern;

/**
 * Shared secret-shape redaction (design ad-audit-log-design.md ADR-A9). Extracted verbatim from
 * {@code DeploymentFailureAnalysisService}'s U6 {@code SECRET_PATTERN} (that class now delegates
 * here instead of keeping its own copy) so the audit domain can apply the exact same redaction to
 * {@code error_summary} without a second, potentially-drifting copy of a security-critical regex
 * (design §7 "레닥션 공용화").
 *
 * <p>This is a small, high-confidence allowlist of common token shapes (GitHub PATs, AWS access
 * key ids, Slack tokens, JWT-like base64 blobs, generic "Bearer &lt;token&gt;" headers) rather than
 * an attempt at exhaustive secret detection — arbitrary custom secrets can still leak through, but
 * redacting the common, reliably-shaped ones meaningfully reduces what ends up in the DB (and, for
 * the deployment-analysis caller, in an LLM request body).</p>
 */
public final class SecretRedactor {

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?:ghp_|gho_|ghs_|github_pat_)[A-Za-z0-9_]{10,}"
                    + "|(?:AKIA|ASIA)[A-Z0-9]{12,}"
                    + "|xox[baprs]-[A-Za-z0-9-]{10,}"
                    + "|eyJ[A-Za-z0-9._-]{20,}"
                    + "|(?i:bearer\\s+\\S{16,})"
    );
    private static final String REDACTED = "***REDACTED***";

    private SecretRedactor() {
    }

    /** Replaces every recognized secret-shaped substring in {@code text} with a fixed placeholder. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SECRET_PATTERN.matcher(text).replaceAll(REDACTED);
    }
}
