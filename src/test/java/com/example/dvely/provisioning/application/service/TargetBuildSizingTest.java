package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
