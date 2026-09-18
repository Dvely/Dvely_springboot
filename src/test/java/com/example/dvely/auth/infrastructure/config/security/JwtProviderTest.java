package com.example.dvely.auth.infrastructure.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.auth.application.port.out.TokenClaims;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.common.exception.UnauthorizedException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * 10-1: 클레임을 한 번의 파싱으로 함께 얻는 경로가 기존 단건 getter 와 정확히 같은 값을 주는지,
 * 그리고 실패를 여전히 한 가지 예외로 뭉개는지 고정한다.
 */
class JwtProviderTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long-for-hs256";

    private final JwtProvider provider =
            new JwtProvider(new JwtProperties(SECRET, 3_600_000L, 2_592_000_000L));

    @Test
    void parseClaimsAgreesWithTheSingleValueGetters() {
        String token = provider.createToken(42L);

        TokenClaims claims = provider.parseClaims(token);

        assertThat(claims.userId()).isEqualTo(42L).isEqualTo(provider.getUserId(token));
        assertThat(claims.jti()).isNotBlank().isEqualTo(provider.getJti(token));
        assertThat(claims.expiresAt()).isEqualTo(provider.getExpiresAt(token));
        assertThat(claims.expiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void everyIssuedTokenCarriesItsOwnJti() {
        // 블랙리스트가 jti 로 토큰을 지목하므로, 같은 유저의 두 토큰이 같은 jti 를 가지면 한 번의
        // 로그아웃이 다른 세션까지 끊는다.
        assertThat(provider.parseClaims(provider.createToken(1L)).jti())
                .isNotEqualTo(provider.parseClaims(provider.createToken(1L)).jti());
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String forged = Jwts.builder()
                .id("forged-jti")
                .subject("42")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(
                        "a-completely-different-secret-of-sufficient-length!!".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> provider.parseClaims(forged))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aCorrectlySignedTokenWithANonNumericSubjectIsRejected() {
        // 서명은 맞지만 subject 가 숫자가 아닌 토큰. parseLong 이 그대로 터져 나가면 인증 필터 밖
        // 호출자(AuthController)가 401 대신 500 을 보게 된다.
        String weird = Jwts.builder()
                .id("jti")
                .subject("not-a-number")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> provider.parseClaims(weird))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> provider.getUserId(weird))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = Jwts.builder()
                .id("jti")
                .subject("42")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> provider.parseClaims(expired))
                .isInstanceOf(UnauthorizedException.class);
    }
}
