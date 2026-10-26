package com.example.dvely.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.common.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GithubAppStateManagerTest {

    private static final JwtProperties JWT = new JwtProperties("test-secret", 3_600_000L, 2_592_000_000L);

    private final InMemoryUsedNonces usedNonces = new InMemoryUsedNonces();
    private final GithubAppStateManager manager = new GithubAppStateManager(JWT, usedNonces);

    @Test
    void anIssuedStateResolvesToItsUserOnce() {
        String state = manager.issue(42L);

        assertThat(manager.consume(state)).isEqualTo(42L);
    }

    @Test
    void aStateCannotBeConsumedTwice() {
        String state = manager.issue(42L);
        manager.consume(state);

        assertThatThrownBy(() -> manager.consume(state))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("이미 처리된");
    }

    @Test
    void aConcurrentConsumeThatLosesTheInsertIsRejected() {
        // 두 콜백이 동시에 "아직 안 쓰임"을 보고 지나가면, 유니크 제약이 뒤의 저장을 막는다.
        usedNonces.failNextInsert = true;
        String state = manager.issue(42L);

        assertThatThrownBy(() -> manager.consume(state))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("이미 처리된");
    }

    @Test
    void aTamperedUserIdIsRejected() {
        String state = manager.issue(42L);
        String signature = state.substring(state.lastIndexOf('.') + 1);
        String forgedPayload = encode("github-app:7:" + Instant.now().getEpochSecond() + ":nonce");

        assertThatThrownBy(() -> manager.consume(forgedPayload + "." + signature))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aStateSignedWithAnotherSecretIsRejected() {
        String foreign = new GithubAppStateManager(
                new JwtProperties("other-secret", 3_600_000L, 2_592_000_000L), new InMemoryUsedNonces())
                .issue(42L);

        assertThatThrownBy(() -> manager.consume(foreign)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAccessJwtOrLoginStateIsNotAccepted() {
        // 예전 state 는 서비스 Access JWT 였고, 로그인 state 도 같은 비밀로 서명된다. 둘 다 받으면 안 된다.
        String loginState = new OAuthStateManager(JWT).generate();

        assertThatThrownBy(() -> manager.consume(loginState)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> manager.consume("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.sig"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> manager.consume(null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anExpiredStateIsRejected() {
        long issuedAt = Instant.now().getEpochSecond() - GithubAppStateManager.EXPIRY_SECONDS - 1;
        String payload = encode("github-app:42:" + issuedAt + ":nonce");
        String expired = payload + "." + signLikeTheManager(payload);

        assertThatThrownBy(() -> manager.consume(expired))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String signLikeTheManager(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    JWT.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryUsedNonces implements TokenBlacklistPort {
        private final Map<String, LocalDateTime> used = new HashMap<>();
        private boolean failNextInsert;

        @Override
        public void revoke(String jti, LocalDateTime expiresAt) {
            if (failNextInsert || used.containsKey(jti)) {
                failNextInsert = false;
                throw new DataIntegrityViolationException("Duplicate entry for uk_revoked_access_tokens_jti");
            }
            used.put(jti, expiresAt);
        }

        @Override
        public boolean isRevoked(String jti) {
            return used.containsKey(jti);
        }
    }
}
