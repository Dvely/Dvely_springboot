package com.example.dvely.auth.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.infrastructure.cache.RevokedTokenCache;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 10-2 를 실제 MySQL 과 실제 빈 배선 위에서 확인한다. 단위 테스트는 어댑터가 캐시를 "어떻게 부르는지"만
 * 보지만, 여기서는 폐기가 <b>정말로 행으로 남아 캐시를 비운 뒤에도 살아남는지</b>를 본다 — 재기동
 * 시나리오에서 실제로 깨질 수 있는 지점이 그 사이(행 커밋 여부)에 있기 때문이다.
 */
@SpringBootTest
class RevokedTokenBlacklistIntegrationTest {

    @Autowired
    private TokenBlacklistPort blacklist;

    @Autowired
    private RevokedTokenCache cache;

    @Test
    void aRevokedTokenStaysRevokedAfterTheProcessRestarts() {
        String jti = uniqueJti();

        blacklist.revoke(jti, LocalDateTime.now().plusHours(1));
        assertThat(blacklist.isRevoked(jti)).isTrue();

        // 재기동 = 프로세스 메모리가 사라진 상태. 여기서 false 가 나오면 로그아웃한 토큰이 배포 한 번에
        // 전부 되살아난다는 뜻이다.
        cache.clear();

        assertThat(blacklist.isRevoked(jti)).isTrue();
    }

    @Test
    void aTokenThatWasNeverRevokedStaysUsableAcrossARestart() {
        String jti = uniqueJti();

        assertThat(blacklist.isRevoked(jti)).isFalse();
        cache.clear();
        assertThat(blacklist.isRevoked(jti)).isFalse();
    }

    private String uniqueJti() {
        // revoked_access_tokens.jti 는 UNIQUE 이고 length=36 이다. 실제 jti 와 같은 형태(UUID)를
        // 그대로 쓴다 - 접두사를 붙이면 36자를 넘겨 잘린다.
        return UUID.randomUUID().toString();
    }
}
