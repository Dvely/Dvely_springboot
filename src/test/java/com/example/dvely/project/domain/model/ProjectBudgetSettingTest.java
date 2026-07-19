package com.example.dvely.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProjectBudgetSettingTest {

    @Test
    void constructor_nullCurrencyDefaultsToUsd() {
        ProjectBudgetSetting setting = new ProjectBudgetSetting(11L, new BigDecimal("10.00"), null);

        assertThat(setting.getCurrency()).isEqualTo("USD");
    }

    @Test
    void constructor_normalizesAmountToScaleTwo() {
        ProjectBudgetSetting setting = new ProjectBudgetSetting(11L, new BigDecimal("10"), "USD");

        assertThat(setting.getMonthlyBudgetAmount()).isEqualByComparingTo("10.00");
        assertThat(setting.getMonthlyBudgetAmount().scale()).isEqualTo(2);
    }

    @Test
    void constructor_rejectsNonUsdCurrencyWithFieldValueInMessage() {
        assertThatThrownBy(() -> new ProjectBudgetSetting(11L, new BigDecimal("10.00"), "usd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 통화입니다")
                .hasMessageContaining("usd");

        assertThatThrownBy(() -> new ProjectBudgetSetting(11L, new BigDecimal("10.00"), "KRW"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW");
    }

    @Test
    void constructor_rejectsZeroOrNegativeAmount() {
        assertThatThrownBy(() -> new ProjectBudgetSetting(11L, BigDecimal.ZERO, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectBudgetSetting(11L, new BigDecimal("-1.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullAmount() {
        assertThatThrownBy(() -> new ProjectBudgetSetting(11L, null, "USD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void update_overwritesAmountAndCurrency() {
        ProjectBudgetSetting setting = new ProjectBudgetSetting(11L, new BigDecimal("10.00"), "USD");

        setting.update(new BigDecimal("25.00"), "USD");

        assertThat(setting.getMonthlyBudgetAmount()).isEqualByComparingTo("25.00");
    }
}
