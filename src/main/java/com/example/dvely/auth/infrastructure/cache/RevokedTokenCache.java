package com.example.dvely.auth.infrastructure.cache;

import com.example.dvely.auth.infrastructure.config.RevokedTokenCacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 폐기(jti) 여부를 기억해 인증 요청마다 DB 를 치지 않게 하는 캐시.
 *
 * <p><b>이 캐시는 정답의 출처가 아니다.</b> DB 가 정본이고 여기는 순수한 단축 경로다 — 항목이 없으면
 * ({@link #lookup}이 {@code null}) 호출자는 반드시 DB 를 봐야 한다. 이 성질 덕분에 재기동으로 캐시가
 * 비거나 크기 상한에 걸려 항목이 밀려나도 폐기된 토큰이 되살아나지 않는다. 완전한 목록을 메모리에
 * 들고 있다가 "없으면 폐기 안 된 것"이라고 답하는 설계는 목록이 한 번이라도 불완전해지는 순간
 * 조용히 열리는 쪽으로 틀리기 때문에 택하지 않았다.</p>
 *
 * <p>캐시를 둘로 나눈 이유는 두 방향의 위험이 다르기 때문이다. "폐기됨"을 놓치면 로그아웃한 토큰이
 * 계속 통과하고(되돌릴 수 없다), "폐기 안 됨"을 잘못 기억하면 사용자가 다시 로그인하면 된다. 그래서
 * 폐기됨 쪽은 토큰이 만료될 때까지 붙잡고, 폐기 안 됨 쪽만 짧은 TTL 로 흘려보낸다.</p>
 */
@Component
public class RevokedTokenCache {

    /** 만료 계산이 Duration 오버플로로 터지지 않게 두는 상한. 실제 토큰 수명은 이보다 한참 짧다. */
    private static final Duration MAX_RETENTION = Duration.ofDays(365);

    /** 폐기된 jti → 그 토큰의 만료 시각. 토큰이 만료되면 서명 검증에서 걸리므로 더 들고 있을 이유가 없다. */
    private final Cache<String, Instant> revoked;

    /** 폐기되지 않은 것으로 DB 에서 확인된 jti. TTL 이 0 이면 아예 만들지 않는다. */
    private final Cache<String, Boolean> notRevoked;

    public RevokedTokenCache(RevokedTokenCacheProperties properties) {
        this.revoked = Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfter(new Expiry<String, Instant>() {
                    @Override
                    public long expireAfterCreate(String jti, Instant expiresAt, long currentTime) {
                        return nanosUntil(expiresAt);
                    }

                    @Override
                    public long expireAfterUpdate(String jti, Instant expiresAt, long currentTime,
                                                  long currentDuration) {
                        return nanosUntil(expiresAt);
                    }

                    @Override
                    public long expireAfterRead(String jti, Instant expiresAt, long currentTime,
                                                long currentDuration) {
                        // 읽었다고 수명을 늘리지 않는다. 만료 시각은 토큰이 정하는 것이지 트래픽이
                        // 정하는 것이 아니다.
                        return currentDuration;
                    }
                })
                .build();

        this.notRevoked = properties.notRevokedTtl().isZero()
                ? null
                : Caffeine.newBuilder()
                        .maximumSize(properties.maximumSize())
                        .expireAfterWrite(properties.notRevokedTtl())
                        .build();
    }

    /**
     * @return {@code TRUE} 폐기됨, {@code FALSE} 폐기 안 됨, {@code null} 모름 — 호출자가 DB 를 봐야 한다
     */
    public Boolean lookup(String jti) {
        // 폐기됨을 먼저 본다. 조회와 폐기가 겹쳐 두 캐시에 같은 jti 가 동시에 들어가는 순간이
        // 있는데, 그때 어느 쪽을 먼저 보느냐가 곧 어느 방향으로 틀리느냐다 — 닫히는 쪽을 먼저 본다.
        if (revoked.getIfPresent(jti) != null) {
            return Boolean.TRUE;
        }
        if (notRevoked != null && notRevoked.getIfPresent(jti) != null) {
            return Boolean.FALSE;
        }
        return null;
    }

    public void rememberRevoked(String jti, LocalDateTime expiresAt) {
        revoked.put(jti, expiresAt.atZone(ZoneId.systemDefault()).toInstant());
        // 같은 JVM 에서 방금 로그아웃한 토큰이 남은 TTL 동안 통과하는 일이 없도록 반대편을 지운다.
        if (notRevoked != null) {
            notRevoked.invalidate(jti);
        }
    }

    public void rememberNotRevoked(String jti) {
        if (notRevoked != null) {
            notRevoked.put(jti, Boolean.TRUE);
        }
    }

    /** 테스트에서 재기동 상황(캐시가 빈 상태)을 만들기 위한 것. */
    public void clear() {
        revoked.invalidateAll();
        if (notRevoked != null) {
            notRevoked.invalidateAll();
        }
    }

    private static long nanosUntil(Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative()) {
            return 0L;
        }
        return (remaining.compareTo(MAX_RETENTION) > 0 ? MAX_RETENTION : remaining).toNanos();
    }
}
