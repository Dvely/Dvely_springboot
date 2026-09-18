package com.example.dvely.project.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.project.application.port.out.GithubRepositoryPort.GithubRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryListCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private final RepositoryListCache cache = new RepositoryListCache();

    private static GithubRepository repository(String fullName) {
        return new GithubRepository(fullName, fullName.split("/")[1], fullName.split("/")[0],
                null, true, "main", OffsetDateTime.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void 다른_사용자의_목록을_돌려주지_않는다() {
        // 이 목록은 그 사용자의 GitHub App 설치에 딸린 저장소다. 키가 사용자 단위가 아니면
        // 남의 비공개 저장소 이름이 그대로 보인다 — 성능 문제가 아니라 권한 사고다.
        cache.put(1L, List.of(repository("alice/secret-app")), NOW);

        assertThat(cache.find(2L, NOW)).isEmpty();
        assertThat(cache.find(1L, NOW)).contains(List.of(repository("alice/secret-app")));
    }

    @Test
    void 사용자마다_자기_목록을_받는다() {
        cache.put(1L, List.of(repository("alice/app")), NOW);
        cache.put(2L, List.of(repository("bob/app")), NOW);

        assertThat(cache.find(1L, NOW)).contains(List.of(repository("alice/app")));
        assertThat(cache.find(2L, NOW)).contains(List.of(repository("bob/app")));
    }

    @Test
    void 수명이_지나면_다시_읽게_한다() {
        cache.put(1L, List.of(repository("alice/app")), NOW);

        assertThat(cache.find(1L, NOW.plus(RepositoryListCache.TTL).minusSeconds(1))).isPresent();
        assertThat(cache.find(1L, NOW.plus(RepositoryListCache.TTL))).isEmpty();
    }

    @Test
    void 무효화하면_그_사용자만_비운다() {
        cache.put(1L, List.of(repository("alice/app")), NOW);
        cache.put(2L, List.of(repository("bob/app")), NOW);

        cache.invalidate(1L);

        assertThat(cache.find(1L, NOW)).isEmpty();
        assertThat(cache.find(2L, NOW)).isPresent();
    }

    @Test
    void 넘겨준_리스트를_나중에_고쳐도_캐시는_바뀌지_않는다() {
        List<GithubRepository> mutable = new ArrayList<>(List.of(repository("alice/app")));
        cache.put(1L, mutable, NOW);

        mutable.add(repository("alice/added-later"));

        assertThat(cache.find(1L, NOW)).contains(List.of(repository("alice/app")));
    }
}
