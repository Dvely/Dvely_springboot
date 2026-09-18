package com.example.dvely.auth.infrastructure.config.security;

import com.example.dvely.auth.application.port.out.TokenClaims;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

/**
 * 서비스용 JWT 발급/검증 (HS256)
 * 클라이언트에게 발급하는 자체 JWT - GitHub App JWT와 별개
 */
@Component
public class JwtProvider implements TokenPort {

    private final JwtProperties jwtProperties;

    /**
     * 서명 키와 파서를 요청마다가 아니라 빈 생성 시 딱 한 번 만든다.
     *
     * <p>{@code Keys.hmacShaKeyFor} 는 매번 시크릿을 바이트로 복사해 새 키 객체를 만들고,
     * 파서 빌드도 매번 새 객체 그래프를 만든다 — 시크릿은 앱 수명 내내 바뀌지 않으므로 반복할 이유가
     * 없다. {@code JwtParser} 는 상태를 갖지 않아 스레드 안전하다(jjwt 문서상 재사용 대상).</p>
     */
    private final SecretKey signingKey;
    private final JwtParser parser;

    public JwtProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(this.signingKey).build();
    }

    @Override
    public String createToken(Long userId) {
        Date now = new Date();

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtProperties.expirationMs()))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public TokenClaims parseClaims(String token) {
        Claims claims = verify(token);
        return new TokenClaims(
                userId(claims),
                claims.getId(),
                toLocalDateTime(claims.getExpiration())
        );
    }

    // 아래 단건 getter 는 클레임 하나만 필요한 호출자(AuthController)를 위해 남긴다. 둘 이상이
    // 필요하면 parseClaims 를 써야 파싱이 한 번으로 끝난다.
    @Override
    public Long getUserId(String token) {
        return userId(verify(token));
    }

    @Override
    public String getJti(String token) {
        return verify(token).getId();
    }

    @Override
    public LocalDateTime getExpiresAt(String token) {
        return toLocalDateTime(verify(token).getExpiration());
    }

    /**
     * 파싱 실패 사유(서명 불일치·만료·형식 오류)를 바깥으로 흘리지 않고 한 가지 예외로 뭉갠다 —
     * 어느 쪽으로 틀렸는지 알려주는 것 자체가 공격자에게 주는 힌트다.
     */
    private Claims verify(String token) {
        try {
            return parser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("유효하지 않은 토큰입니다");
        }
    }

    /** subject 가 숫자가 아닌 토큰도 "유효하지 않은 토큰"으로 똑같이 뭉갠다 — 위 verify 와 같은 이유. */
    private Long userId(Claims claims) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("유효하지 않은 토큰입니다");
        }
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
