package com.example.dvely.common.limit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서버 전체 동시 실행 상한(#624). 사용자별 상한({@link UserLimitProperties})은 한 사람이 독점하지 못하게 할 뿐, 사용자가
 * 늘면 합이 얼마든지 커진다. 이 상한이 있어야 동시에 뜰 수 있는 컨테이너 수가 정해지고, 그것으로 서버 사양을 역산할 수 있다.
 *
 * <p>0 이하를 "끄기"로 받지 않고 기동을 멈춘다({@link UserLimitProperties} 와 같은 이유). 끄고 싶으면 큰 값을 넣는다.
 * 사용자별 상한보다 작아도 된다 — 그때는 이 값이 먼저 걸린다.</p>
 *
 * @param maxLivePreviews          서버 전체의 살아 있는(ACTIVE·PROVISIONING) 프리뷰 세션 수 — 세션마다 컨테이너가 하나다
 * @param maxCodingAgentContainers 동시에 도는 코딩 에이전트(CLAUDE_CODE·CODEX) 실행 수 — 실행마다 에이전트 컨테이너가 하나다
 *                                 (egress 프록시를 켜면 작은 프록시 컨테이너가 하나 더 붙는다)
 */
@ConfigurationProperties("qeploy.limits.global")
public record GlobalLimitProperties(
        @DefaultValue("4") int maxLivePreviews,
        @DefaultValue("2") int maxCodingAgentContainers
) {

    public GlobalLimitProperties {
        if (maxLivePreviews < 1) {
            throw new IllegalArgumentException(
                    "qeploy.limits.global.max-live-previews 는 1 이상이어야 합니다: " + maxLivePreviews);
        }
        if (maxCodingAgentContainers < 1) {
            throw new IllegalArgumentException(
                    "qeploy.limits.global.max-coding-agent-containers 는 1 이상이어야 합니다: " + maxCodingAgentContainers);
        }
    }
}
