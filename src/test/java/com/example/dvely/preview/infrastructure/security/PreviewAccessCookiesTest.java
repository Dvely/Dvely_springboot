package com.example.dvely.preview.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 소유권 쿠키의 위조·전용(轉用) 방어 (Issue #77 G2).
 *
 * <p>이 쿠키가 게이트웨이의 유일한 인가 근거이므로, "서명만 맞으면 통과"로 느슨해지는 순간 A 세션
 * 쿠키로 B 세션을 여는 길이 열린다. 대상 세션·소유자·만료를 모두 보는지 여기서 고정한다.</p>
 */
class PreviewAccessCookiesTest {

    private static final String SESSION_ID = "session-1";
    private static final Long OWNER = 7L;

    private final PreviewAccessCookies cookies = new PreviewAccessCookies(
            new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L));

    @Test
    void acceptsTheCookieItIssuedForThatSessionAndOwner() {
        String value = cookies.issue(SESSION_ID, OWNER, Duration.ofMinutes(30));

        assertThat(cookies.isValid(value, SESSION_ID, OWNER)).isTrue();
    }

    @Test
    void rejectsACookieIssuedForAnotherSession() {
        String value = cookies.issue("other-session", OWNER, Duration.ofMinutes(30));

        assertThat(cookies.isValid(value, SESSION_ID, OWNER)).isFalse();
    }

    @Test
    void rejectsACookieIssuedForAnotherOwner() {
        String value = cookies.issue(SESSION_ID, 99L, Duration.ofMinutes(30));

        assertThat(cookies.isValid(value, SESSION_ID, OWNER)).isFalse();
    }

    @Test
    void rejectsAnExpiredCookie() {
        String value = cookies.issue(SESSION_ID, OWNER, Duration.ofSeconds(-1));

        assertThat(cookies.isValid(value, SESSION_ID, OWNER)).isFalse();
    }

    @Test
    void rejectsATamperedPayloadAndSignature() {
        String value = cookies.issue(SESSION_ID, OWNER, Duration.ofMinutes(30));
        String payload = value.substring(0, value.lastIndexOf('.'));
        String signature = value.substring(value.lastIndexOf('.') + 1);

        assertThat(cookies.isValid(payload + "x." + signature, SESSION_ID, OWNER)).isFalse();
        assertThat(cookies.isValid(payload + "." + signature + "x", SESSION_ID, OWNER)).isFalse();
    }

    /** 다른 키로 서명된 값은 통과하면 안 된다 — 키를 모르면 만들 수 없어야 한다. */
    @Test
    void rejectsACookieSignedWithAnotherSecret() {
        PreviewAccessCookies attacker = new PreviewAccessCookies(
            new JwtProperties("another-secret-key-that-is-long-enough", 3600000L, 7200000L));

        String forged = attacker.issue(SESSION_ID, OWNER, Duration.ofMinutes(30));

        assertThat(cookies.isValid(forged, SESSION_ID, OWNER)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedValues() {
        assertThat(cookies.isValid(null, SESSION_ID, OWNER)).isFalse();
        assertThat(cookies.isValid("", SESSION_ID, OWNER)).isFalse();
        assertThat(cookies.isValid("no-separator", SESSION_ID, OWNER)).isFalse();
        assertThat(cookies.isValid(".", SESSION_ID, OWNER)).isFalse();
    }

    /** 쿠키가 다른 세션 경로로 새어 나가지 않도록 Path는 세션 단위로 좁힌다. */
    @Test
    void scopesTheCookiePathToTheSession() {
        assertThat(cookies.cookiePath(SESSION_ID)).isEqualTo("/api/v1/previews/session-1/");
    }
}
