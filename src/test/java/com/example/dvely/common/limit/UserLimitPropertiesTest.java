package com.example.dvely.common.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserLimitPropertiesTest {

    @Test
    void zeroOrLessStopsStartupInsteadOfMeaningNoLimit() {
        // 0 을 "상한 없음"으로 읽으면 잘못 넣은 값 하나로 상한이 조용히 사라진다.
        assertThatThrownBy(() -> new UserLimitProperties(0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-active-runs");
        assertThatThrownBy(() -> new UserLimitProperties(2, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-live-previews");
    }

    @Test
    void acceptsPositiveValues() {
        UserLimitProperties limits = new UserLimitProperties(1, 1);

        assertThat(limits.maxActiveRuns()).isEqualTo(1);
        assertThat(limits.maxLivePreviews()).isEqualTo(1);
    }
}
