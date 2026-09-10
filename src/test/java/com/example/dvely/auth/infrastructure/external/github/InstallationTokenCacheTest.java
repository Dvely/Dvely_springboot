package com.example.dvely.auth.infrastructure.external.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstallationTokenCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private final InstallationTokenCache cache = new InstallationTokenCache();

    @Test
    void 남은_수명이_충분하면_같은_토큰을_다시_준다() {
        cache.put(1L, "ghs-token", NOW.plus(Duration.ofHours(1)), NOW);

        assertThat(cache.find(1L, NOW)).contains("ghs-token");
    }

    @Test
    void 만료_여유_안으로_들어온_토큰은_건네지_않는다() {
        // 여유가 없으면 만료 직전 토큰을 받아 쓴 호출이 401 을 맞는다 — 캐시가 없을 때는
        // 나지 않던 실패다.
        cache.put(1L, "ghs-token", NOW.plus(Duration.ofMinutes(4)), NOW);

        assertThat(cache.find(1L, NOW)).isEmpty();
    }

    @Test
    void 여유_경계_바로_바깥은_아직_쓴다() {
        cache.put(1L, "ghs-token", NOW.plus(InstallationTokenCache.EXPIRY_MARGIN).plusSeconds(1), NOW);

        assertThat(cache.find(1L, NOW)).contains("ghs-token");
    }

    @Test
    void 설치마다_토큰이_섞이지_않는다() {
        // 남의 설치 토큰을 건네주면 그 사용자의 저장소에 접근하게 된다.
        cache.put(1L, "token-of-installation-1", NOW.plus(Duration.ofHours(1)), NOW);
        cache.put(2L, "token-of-installation-2", NOW.plus(Duration.ofHours(1)), NOW);

        assertThat(cache.find(1L, NOW)).contains("token-of-installation-1");
        assertThat(cache.find(2L, NOW)).contains("token-of-installation-2");
        assertThat(cache.find(3L, NOW)).isEmpty();
    }

    @Test
    void expires_at_을_받지_못하면_문서상_수명으로_잡는다() {
        cache.put(1L, "ghs-token", null, NOW);

        Instant beforeMargin = NOW.plus(InstallationTokenCache.DEFAULT_LIFETIME)
                .minus(InstallationTokenCache.EXPIRY_MARGIN)
                .minusSeconds(1);
        assertThat(cache.find(1L, beforeMargin)).contains("ghs-token");
        assertThat(cache.find(1L, beforeMargin.plusSeconds(2))).isEmpty();
    }

    @Test
    void 시간이_지나면_스스로_다시_발급받게_한다() {
        cache.put(1L, "ghs-token", NOW.plus(Duration.ofHours(1)), NOW);

        assertThat(cache.find(1L, NOW.plus(Duration.ofHours(2)))).isEmpty();
    }
}
