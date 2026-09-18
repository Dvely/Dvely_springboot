package com.example.dvely.auth.infrastructure.external.github;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub App installation access token 의 단기 캐시.
 *
 * <p>토큰은 1 시간짜리인데 프로젝트 생성·푸시·브랜치 작업이 매번 새로 발급받고 있었다. 발급 자체가
 * RSA 서명 + 왕복 한 번이라, 한 흐름 안에서만도 같은 토큰을 여러 번 산다.</p>
 *
 * <p>여유({@link #EXPIRY_MARGIN})를 두는 것이 핵심이다. 만료 1 초 전 토큰을 건네주면 그것을 받아
 * 쓰는 호출이 401 을 맞는다 — 캐시가 없을 때는 나지 않던 실패다. 5 분은 두 가지를 함께 덮는다:
 * 호스트 시계와 GitHub 시계의 어긋남, 그리고 토큰을 받아간 호출이 끝나기까지 걸리는 시간(이
 * 저장소의 GitHub 호출은 U1 이후 최대 60 초로 묶인다). 즉 여유 안에서 만료되는 토큰은 건네지
 * 않으므로, 건네진 토큰이 쓰이는 도중 만료되는 일은 생기지 않는다.</p>
 *
 * <p>토큰 문자열을 담고 있으므로 이 클래스와 항목은 <b>toString 을 두지 않는다</b>. 로그·예외에
 * 실려 나갈 경로를 애초에 만들지 않는다.</p>
 */
class InstallationTokenCache {

    /** 남은 수명이 이보다 짧으면 캐시에 있어도 만료로 본다. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /** GitHub 이 expires_at 을 주지 않거나 읽을 수 없을 때 가정하는 수명(문서상 값). */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    Optional<String> find(Long installationId, Instant now) {
        Entry entry = entries.get(installationId);
        if (entry == null || !entry.usableAt(now)) {
            return Optional.empty();
        }
        return Optional.of(entry.token);
    }

    void put(Long installationId, String token, Instant expiresAt, Instant now) {
        Instant effective = expiresAt == null ? now.plus(DEFAULT_LIFETIME) : expiresAt;
        entries.put(installationId, new Entry(token, effective));
        purgeExpired(now);
    }

    /**
     * 만료된 항목을 걷어낸다. 설치가 늘기만 하고 줄지 않으면 맵이 계속 자라기 때문이다.
     *
     * <p>{@code put} 은 캐시 미스일 때만 불리고, 그 직전에 이미 GitHub 왕복이 한 번 있었다.
     * 그 비용 옆에서 항목 수만큼의 순회는 무시할 수 있다.</p>
     */
    private void purgeExpired(Instant now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().usableAt(now)) {
                iterator.remove();
            }
        }
    }

    /** 비밀을 담으므로 record 가 아니라 toString 없는 클래스로 둔다. */
    private static final class Entry {

        private final String token;
        private final Instant expiresAt;

        private Entry(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        private boolean usableAt(Instant now) {
            return now.isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }
    }
}
