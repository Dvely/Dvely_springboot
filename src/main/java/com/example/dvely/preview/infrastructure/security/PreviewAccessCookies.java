package com.example.dvely.preview.infrastructure.security;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 접근을 세션 소유자에게 묶는 쿠키의 발급·검증 (Issue #77 G2).
 *
 * <p>게이트웨이는 iframe이 헤더 없이 부르는 경로라 `Authorization`을 요구할 수 없다. 그래서
 * 소유권 증명을 쿠키로 옮긴다: 인증된 요청으로 한 번 발급받고, 이후 문서·서브리소스 요청에는
 * 브라우저가 알아서 실어 보낸다. 쿠키는 `/api/v1/previews/{sessionId}/`로 경로가 좁혀져 있어
 * 다른 세션이나 다른 API 경로로는 새어 나가지 않는다.</p>
 *
 * <p>값은 서버만 만들 수 있으면 되고 조회할 필요가 없으므로, DB 컬럼을 늘리는 대신
 * {@code sessionId:ownerUserId:exp}를 HMAC-SHA256으로 서명한 문자열을 그대로 담는다
 * ({@code OAuthStateManager}가 쓰는 것과 같은 방식·같은 키). 검증은 서명·만료·대상 세션·소유자
 * 일치를 모두 본다 — 서명만 맞으면 통과시키면 A 세션 쿠키로 B 세션을 여는 길이 열린다.</p>
 */
@Component
@RequiredArgsConstructor
public class PreviewAccessCookies {

    public static final String COOKIE_NAME = "qeploy_preview_access";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ".";

    private final JwtProperties jwtProperties;

    /** 이 세션 전용 쿠키 경로. accessToken은 회전하므로 경로에 포함하지 않는다. */
    public String cookiePath(String sessionId) {
        return "/api/v1/previews/" + sessionId + "/";
    }

    public String issue(String sessionId, Long ownerUserId, Duration validity) {
        long expiresAt = Instant.now().plus(validity).getEpochSecond();
        String payload = sessionId + ":" + ownerUserId + ":" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + SEPARATOR + sign(encoded);
    }

    /**
     * 쿠키가 이 세션·이 소유자의 것으로 유효한지. 형식 오류·서명 불일치·만료·대상 불일치는 모두
     * 같은 {@code false}다 — 어느 쪽으로 틀렸는지 알려주는 것 자체가 정보다.
     */
    public boolean isValid(String cookieValue, String sessionId, Long ownerUserId) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return false;
        }
        int separator = cookieValue.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == cookieValue.length() - 1) {
            return false;
        }
        String encoded = cookieValue.substring(0, separator);
        String signature = cookieValue.substring(separator + 1);
        if (!constantTimeEquals(sign(encoded), signature)) {
            return false;
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            return parts[0].equals(sessionId)
                    && parts[1].equals(String.valueOf(ownerUserId))
                    && Long.parseLong(parts[2]) > Instant.now().getEpochSecond();
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    jwtProperties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception exception) {
            throw new IllegalStateException("Preview 접근 쿠키 서명 생성 실패", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
