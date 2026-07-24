package com.example.dvely.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Design ad-audit-log-design.md ADR-A9 — this pattern was extracted verbatim from
 * {@code DeploymentFailureAnalysisService}'s U6 {@code SECRET_PATTERN}; that class's own
 * {@code DeploymentFailureAnalysisServiceTest#secretsInLogsAreRedactedBeforeStorageAndBeforeReachingTheLlm}
 * is the no-regression check for the delegation itself. This class covers the five token shapes
 * directly plus the "leave ordinary text alone" case.
 */
class SecretRedactorTest {

    @Test
    void redactsGithubPersonalAccessToken() {
        String text = "token: " + "ghp_" + "1234567890abcdefghijklmno";

        assertThat(SecretRedactor.redact(text))
                .doesNotContain("1234567890abcdefghijklmno")
                .contains("***REDACTED***");
    }

    @Test
    void redactsAwsAccessKeyId() {
        String text = "aws key: " + "AKIA" + "ABCDEFGHIJKLMNOP";

        assertThat(SecretRedactor.redact(text))
                .doesNotContain("ABCDEFGHIJKLMNOP")
                .contains("***REDACTED***");
    }

    @Test
    void redactsSlackToken() {
        String text = "slack: xoxb-1234567890-abcdefghij";

        assertThat(SecretRedactor.redact(text))
                .doesNotContain("xoxb-1234567890-abcdefghij")
                .contains("***REDACTED***");
    }

    @Test
    void redactsJwtLikeToken() {
        String text = "jwt: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PYtRAzJj8HH8";

        assertThat(SecretRedactor.redact(text))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("***REDACTED***");
    }

    @Test
    void redactsBearerHeader() {
        String text = "Authorization: Bearer abcdef1234567890zzzz";

        assertThat(SecretRedactor.redact(text))
                .doesNotContain("Bearer abcdef1234567890zzzz")
                .contains("***REDACTED***");
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        String text = "배포가 정상적으로 완료되었습니다. version=v12";

        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(SecretRedactor.redact(null)).isNull();
        assertThat(SecretRedactor.redact("")).isEmpty();
    }
}
