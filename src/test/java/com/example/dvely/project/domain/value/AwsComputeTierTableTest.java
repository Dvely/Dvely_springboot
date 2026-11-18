package com.example.dvely.project.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AwsComputeTierTableTest {

    @Test
    void eachTierLaunchesTheMatchingT3Size() {
        assertThat(AwsComputeTierTable.of(ComputeTier.MICRO).instanceType()).isEqualTo("t3.micro");
        assertThat(AwsComputeTierTable.of(ComputeTier.SMALL).instanceType()).isEqualTo("t3.small");
        assertThat(AwsComputeTierTable.of(ComputeTier.MEDIUM).instanceType()).isEqualTo("t3.medium");
        assertThat(AwsComputeTierTable.of(ComputeTier.LARGE).instanceType()).isEqualTo("t3.large");
    }

    @Test
    void theMonthlyEstimateKeepsThePublishedPrices() {
        // 이 PR 은 가격을 바꾸지 않는다. 숫자가 바뀌면 PRICE_TABLE_VERSION 도 같이 올려야 한다.
        assertThat(AwsComputeTierTable.of(ComputeTier.MICRO).monthlyUsd()).isEqualByComparingTo("8.50");
        assertThat(AwsComputeTierTable.of(ComputeTier.SMALL).monthlyUsd()).isEqualByComparingTo("17.00");
        assertThat(AwsComputeTierTable.of(ComputeTier.MEDIUM).monthlyUsd()).isEqualByComparingTo("34.00");
        assertThat(AwsComputeTierTable.of(ComputeTier.LARGE).monthlyUsd()).isEqualByComparingTo("68.00");
    }

    @Test
    void withoutASettingTheServerFallsBackToTheSmallestTier() {
        assertThat(AwsComputeTierTable.fallbackInstanceType()).isEqualTo("t3.micro");
    }
}
