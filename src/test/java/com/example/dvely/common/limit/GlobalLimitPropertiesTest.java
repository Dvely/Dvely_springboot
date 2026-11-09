package com.example.dvely.common.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GlobalLimitPropertiesTest {

    @Test
    void zeroOrLessStopsStartupInsteadOfMeaningNoLimit() {
        // 0 을 "상한 없음"으로 읽으면 잘못 넣은 값 하나로 서버 전체 상한이 조용히 사라진다.
        assertThatThrownBy(() -> new GlobalLimitProperties(0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-live-previews");
        assertThatThrownBy(() -> new GlobalLimitProperties(4, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-coding-agent-containers");
    }

    @Test
    void mayBeSmallerThanThePerUserLimit() {
        // 작은 서버에서는 서버 전체 상한이 사용자별 상한(프리뷰 3)보다 먼저 걸리는 것이 정상이다.
        GlobalLimitProperties limits = new GlobalLimitProperties(1, 1);

        assertThat(limits.maxLivePreviews()).isEqualTo(1);
        assertThat(limits.maxCodingAgentContainers()).isEqualTo(1);
    }
}
