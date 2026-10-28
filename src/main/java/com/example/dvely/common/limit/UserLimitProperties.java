package com.example.dvely.common.limit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사용자별 동시 실행 상한(#509). 한 사용자가 실행 스레드와 프리뷰 컨테이너를 독점하지 않게 한다.
 *
 * <p>0 이하를 "끄기"로 받지 않고 기동을 멈춘다. 잘못 넣은 값이 상한 없음으로 조용히 바뀌면 상한을 둔 의미가 없다.
 * 끄고 싶으면 충분히 큰 값을 넣는다.</p>
 *
 * @param maxActiveRuns   기계가 일하고 있는(PENDING·QUEUED·RETRY_WAIT·RUNNING) 에이전트 런 수
 * @param maxLivePreviews 살아 있는(ACTIVE·PROVISIONING) 프리뷰 세션 수 — 세션마다 컨테이너가 하나다
 */
@ConfigurationProperties("qeploy.limits.per-user")
public record UserLimitProperties(
        @DefaultValue("2") int maxActiveRuns,
        @DefaultValue("3") int maxLivePreviews
) {

    public UserLimitProperties {
        if (maxActiveRuns < 1) {
            throw new IllegalArgumentException(
                    "qeploy.limits.per-user.max-active-runs 는 1 이상이어야 합니다: " + maxActiveRuns);
        }
        if (maxLivePreviews < 1) {
            throw new IllegalArgumentException(
                    "qeploy.limits.per-user.max-live-previews 는 1 이상이어야 합니다: " + maxLivePreviews);
        }
    }
}
