package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.project.domain.value.AwsComputeTierTable;
import com.example.dvely.project.domain.value.ComputeTier;
import org.junit.jupiter.api.Test;

class TargetBuildSizingTest {

    @Test
    void nanoAndMicroAreTooSmallForAJvmBuild() {
        assertThat(TargetBuildSizing.tooSmallForJvm("t3.micro")).isTrue();
        assertThat(TargetBuildSizing.tooSmallForJvm("t3.nano")).isTrue();
        assertThat(TargetBuildSizing.tooSmallForJvm("t4g.micro")).isTrue();
        assertThat(TargetBuildSizing.tooSmallForJvm(" T2.MICRO ")).isTrue();
    }

    @Test
    void smallAndAboveAreEnough() {
        assertThat(TargetBuildSizing.tooSmallForJvm("t3.small")).isFalse();
        assertThat(TargetBuildSizing.tooSmallForJvm("t3.medium")).isFalse();
        assertThat(TargetBuildSizing.tooSmallForJvm("c6i.large")).isFalse();
        assertThat(TargetBuildSizing.tooSmallForJvm(null)).isFalse();
    }

    @Test
    void theMessageNamesTheTypeAndTheMinimum() {
        assertThat(TargetBuildSizing.jvmTooSmallMessage("t3.micro")).contains("t3.micro").contains("t3.small");
    }

    @Test
    void typesComingFromTheComputeTierKeepTheSameJudgement() {
        // #597 뒤로 요청이 비면 등급이 유형을 정한다. 판정은 유형 문자열만 보므로 MICRO 만 막히고 SMALL 부터 통과해야 한다.
        String micro = AwsComputeTierTable.of(ComputeTier.MICRO).instanceType();
        assertThat(TargetBuildSizing.tooSmallForJvm(micro)).isTrue();
        assertThat(TargetBuildSizing.jvmTooSmallMessage(micro)).contains("t3.micro").contains("t3.small 이상");

        for (ComputeTier tier : new ComputeTier[] {ComputeTier.SMALL, ComputeTier.MEDIUM, ComputeTier.LARGE}) {
            assertThat(TargetBuildSizing.tooSmallForJvm(AwsComputeTierTable.of(tier).instanceType()))
                    .as("%s", tier).isFalse();
        }
    }
}
