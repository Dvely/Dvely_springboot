package com.example.dvely.auth.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.infrastructure.cache.RevokedTokenCache;
import com.example.dvely.auth.infrastructure.config.RevokedTokenCacheProperties;
import com.example.dvely.auth.infrastructure.persistence.entity.RevokedTokenEntity;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 10-2 의 보안 성질을 고정한다. 이 캐시가 틀리는 방향에는 두 가지가 있는데 값이 완전히 다르다 —
 * 폐기된 토큰을 통과시키면 로그아웃이 무력화되고(되돌릴 수 없다), 멀쩡한 토큰을 막으면 사용자가 다시
 * 로그인하면 된다. 아래 테스트들은 전자가 일어나지 않는다는 것만 집중해서 못박는다.
 */
class RevokedTokenRepositoryAdapterTest {

    private static final LocalDateTime IN_AN_HOUR = LocalDateTime.now().plusHours(1);

    private SpringDataRevokedTokenRepository repository;
    private RevokedTokenCache cache;
    private RevokedTokenRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataRevokedTokenRepository.class);
        cache = new RevokedTokenCache(new RevokedTokenCacheProperties(Duration.ofSeconds(60), 10_000L));
        adapter = new RevokedTokenRepositoryAdapter(repository, cache);
    }

    @Test
    void aCacheMissAlwaysConsultsTheDatabase() {
        when(repository.findByJti("unknown")).thenReturn(Optional.empty());

        assertThat(adapter.isRevoked("unknown")).isFalse();

        verify(repository, times(1)).findByJti("unknown");
    }

    @Test
    void aRepeatedCheckOfTheSameTokenNoLongerTouchesTheDatabase() {
        // 이 단위의 목적 자체. 폐기되지 않은 토큰(=거의 모든 요청)이 매번 DB 를 치던 것을 없앤다.
        when(repository.findByJti("live-jti")).thenReturn(Optional.empty());

        adapter.isRevoked("live-jti");
        adapter.isRevoked("live-jti");
        adapter.isRevoked("live-jti");

        verify(repository, times(1)).findByJti("live-jti");
    }

    @Test
    void aRevokedTokenFoundInTheDatabaseIsNotAskedAboutAgain() {
        when(repository.findByJti("dead-jti"))
                .thenReturn(Optional.of(new RevokedTokenEntity("dead-jti", IN_AN_HOUR)));

        assertThat(adapter.isRevoked("dead-jti")).isTrue();
        assertThat(adapter.isRevoked("dead-jti")).isTrue();

        verify(repository, times(1)).findByJti("dead-jti");
    }

    @Test
    void revocationIsStillFoundAfterTheCacheIsEmptiedAsItIsOnRestart() {
        // 재기동하면 캐시는 빈다. 그때 DB 를 보지 않는 설계였다면 폐기된 토큰이 전부 되살아난다.
        when(repository.findByJti("dead-jti"))
                .thenReturn(Optional.of(new RevokedTokenEntity("dead-jti", IN_AN_HOUR)));
        adapter.revoke("dead-jti", IN_AN_HOUR);
        assertThat(adapter.isRevoked("dead-jti")).isTrue();

        cache.clear();

        assertThat(adapter.isRevoked("dead-jti")).isTrue();
        verify(repository, times(1)).findByJti("dead-jti");
    }

    @Test
    void revokingImmediatelyOverridesAnAlreadyCachedNotRevokedAnswer() {
        // 같은 JVM 에서 방금 로그아웃한 토큰이 부정 캐시 TTL 이 남았다는 이유로 계속 통과하면
        // 로그아웃 버튼이 아무 일도 하지 않는 것과 같다.
        when(repository.findByJti("jti")).thenReturn(Optional.empty());
        assertThat(adapter.isRevoked("jti")).isFalse();

        adapter.revoke("jti", IN_AN_HOUR);

        assertThat(adapter.isRevoked("jti")).isTrue();
        // 캐시에서 바로 답이 나와야 한다 - 폐기 직후 DB 를 다시 볼 필요가 없다.
        verify(repository, times(1)).findByJti("jti");
    }

    @Test
    void revocationIsVisibleBeforeTheRowIsEvenWritten() {
        // 로그아웃은 @Transactional 안에서 일어나 행 커밋은 나중이다. 그 사이 요청이 통과하면 안 된다.
        adapter.revoke("jti", IN_AN_HOUR);

        assertThat(adapter.isRevoked("jti")).isTrue();
        verify(repository, never()).findByJti(anyString());
        verify(repository, times(1)).save(any(RevokedTokenEntity.class));
    }

    @Test
    void anEntryForAnAlreadyExpiredTokenIsNotRemembered() {
        // 만료된 토큰은 어차피 서명 검증에서 걸린다. 캐시가 붙잡고 있을 이유가 없고, 붙잡으면
        // 만료 시각과 무관하게 자리만 차지한다.
        when(repository.findByJti("expired")).thenReturn(Optional.empty());
        adapter.revoke("expired", LocalDateTime.now().minusSeconds(1));

        assertThat(cache.lookup("expired")).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void aZeroTtlTurnsTheNotRevokedCacheOffAndRestoresTheAlwaysAskDatabaseBehaviour() {
        // 다중 인스턴스에서 로그아웃 전파 지연조차 받아들일 수 없을 때의 탈출구.
        RevokedTokenCache noNegativeCache =
                new RevokedTokenCache(new RevokedTokenCacheProperties(Duration.ZERO, 10_000L));
        RevokedTokenRepositoryAdapter strict =
                new RevokedTokenRepositoryAdapter(repository, noNegativeCache);
        when(repository.findByJti("jti")).thenReturn(Optional.empty());

        strict.isRevoked("jti");
        strict.isRevoked("jti");

        verify(repository, times(2)).findByJti("jti");
    }
}
