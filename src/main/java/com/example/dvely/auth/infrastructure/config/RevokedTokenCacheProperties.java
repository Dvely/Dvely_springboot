package com.example.dvely.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 폐기 토큰 캐시 정책. 기본값을 코드에 두어 yml 을 건드리지 않아도 동작한다.
 *
 * @param notRevokedTtl "이 jti 는 폐기되지 않았다"를 기억해 두는 시간. 이 값이 곧 <b>다중 인스턴스에서
 *                      로그아웃이 다른 인스턴스까지 전파되는 최대 지연</b>이다. 폐기를 수행한 인스턴스
 *                      자신은 즉시 반영되므로(폐기 시 이 항목을 지운다) 단일 인스턴스에서는 지연이 없다.
 *                      {@code 0} 을 주면 부정 캐시를 끄고 매 요청 DB 를 보던 U10 이전 동작으로 돌아간다.
 * @param maximumSize   두 캐시 각각의 상한. 넘치면 오래된 항목부터 버리는데, 버려진 항목은 다음 조회에서
 *                      DB 를 다시 보게 될 뿐이라 정답이 달라지지 않는다.
 */
@ConfigurationProperties(prefix = "qeploy.auth.revoked-token-cache")
public record RevokedTokenCacheProperties(
        Duration notRevokedTtl,
        long maximumSize
) {
    private static final Duration DEFAULT_NOT_REVOKED_TTL = Duration.ofSeconds(60);
    private static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    public RevokedTokenCacheProperties {
        if (notRevokedTtl == null || notRevokedTtl.isNegative()) {
            notRevokedTtl = DEFAULT_NOT_REVOKED_TTL;
        }
        if (maximumSize <= 0) {
            maximumSize = DEFAULT_MAXIMUM_SIZE;
        }
    }
}
