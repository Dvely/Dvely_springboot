package com.example.dvely.auth.infrastructure.oauth;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

/**
 * GitHub App 설치·재인증 URL 의 {@code state}. 예전에는 서비스 Access JWT 를 그대로 실었다 — 토큰이
 * GitHub 쪽 URL 과 브라우저 히스토리에 남았고, 콜백은 서명과 만료만 봐서 로그아웃한 토큰도 받았다(#504).
 *
 * <p>지금은 "누가 요청했는가(userId)"만 담은 1회용 값이다. 서명은 {@link OAuthStateManager} 와 같은
 * HMAC 이고, 용도 접두사를 서명 안에 넣어 로그인 state 와 서로 바꿔 쓸 수 없게 했다.</p>
 *
 * <p>1회용은 폐기 토큰 테이블을 빌려 지킨다. nonce 가 UUID 라 JWT jti 와 겹치지 않고, 만료 행은
 * 기존 정리 스케줄러가 치운다. 같은 nonce 가 동시에 두 번 들어와도 jti 유니크 제약이 하나만 남긴다.</p>
 */
@Component
@RequiredArgsConstructor
public class GithubAppStateManager {

    // 설치 화면에서 저장소를 고르는 시간까지 넉넉히 둔다. 넘기면 설치를 다시 시작하면 된다.
    static final long EXPIRY_SECONDS = 900;
    private static final String PURPOSE = "github-app";
    private static final String ALGORITHM = "HmacSHA256";

    private final JwtProperties jwtProperties;
    private final TokenBlacklistPort usedNonces;

    public String issue(Long userId) {
        String payload = PURPOSE + ":" + userId + ":" + Instant.now().getEpochSecond() + ":" + UUID.randomUUID();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    /** 서명·만료를 확인하고 nonce 를 소모한 뒤 요청한 userId 를 돌려준다. 두 번째 호출은 거부한다. */
    public Long consume(String state) {
        int dotIndex = state == null ? -1 : state.lastIndexOf('.');
        if (dotIndex <= 0) {
            throw invalid();
        }
        String encodedPayload = state.substring(0, dotIndex);
        String signature = state.substring(dotIndex + 1);
        if (!MessageDigest.isEqual(
                sign(encodedPayload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }

        String[] parts = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
                .split(":");
        if (parts.length != 4 || !PURPOSE.equals(parts[0])) {
            throw invalid();
        }
        long issuedAt = Long.parseLong(parts[2]);
        if (Instant.now().getEpochSecond() - issuedAt > EXPIRY_SECONDS) {
            throw new UnauthorizedException("GitHub App 연동 요청이 만료되었습니다. 다시 시도해 주세요.");
        }

        String nonce = parts[3];
        if (usedNonces.isRevoked(nonce)) {
            throw reused();
        }
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(issuedAt + EXPIRY_SECONDS), ZoneId.systemDefault());
        try {
            usedNonces.revoke(nonce, expiresAt);
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 이 저장 사이에 같은 nonce 가 먼저 소모됐다.
            throw reused();
        }
        return Long.parseLong(parts[1]);
    }

    private UnauthorizedException invalid() {
        return new UnauthorizedException("유효하지 않은 GitHub App 연동 요청입니다.");
    }

    private UnauthorizedException reused() {
        return new UnauthorizedException("이미 처리된 GitHub App 연동 요청입니다. 다시 시도해 주세요.");
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    jwtProperties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("GitHub App state 서명 생성 실패", e);
        }
    }
}
