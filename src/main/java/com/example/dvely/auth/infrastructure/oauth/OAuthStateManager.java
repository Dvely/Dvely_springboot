package com.example.dvely.auth.infrastructure.oauth;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OAuthStateManager {

    private static final long EXPIRY_SECONDS = 300; // 5분
    private static final String ALGORITHM = "HmacSHA256";

    private final JwtProperties jwtProperties;

    public String generate() {
        String payload = Instant.now().getEpochSecond() + ":" + UUID.randomUUID();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    public void verify(String state) {
        if (state == null || !state.contains(".")) {
            throw new IllegalArgumentException("유효하지 않은 OAuth state 형식입니다");
        }

        int dotIndex = state.lastIndexOf('.');
        String encodedPayload = state.substring(0, dotIndex);
        String signature = state.substring(dotIndex + 1);

        if (!sign(encodedPayload).equals(signature)) {
            throw new IllegalArgumentException("OAuth state 서명이 일치하지 않습니다");
        }

        String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        long issuedAt = Long.parseLong(payload.split(":")[0]);
        if (Instant.now().getEpochSecond() - issuedAt > EXPIRY_SECONDS) {
            throw new IllegalArgumentException("OAuth state가 만료되었습니다");
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    jwtProperties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("OAuth state 서명 생성 실패", e);
        }
    }
}
