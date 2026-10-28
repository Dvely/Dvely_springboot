package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.provisioning.application.service.ContainerImageBuilder.Mode;
import com.example.dvely.provisioning.infrastructure.config.Ec2ProvisioningProperties;
import org.junit.jupiter.api.Test;

/** 빌드 격리 방식 판정(#507): 호스트 데몬에서 사용자 Dockerfile 을 도는 길은 명시 설정으로만 연다. */
class ContainerImageBuilderModeTest {

    @Test
    void kanikoIsTheDefaultWhenNothingIsConfigured() {
        assertThat(props(null, null).useKaniko()).isTrue();
        assertThat(props("", null).useKaniko()).isTrue();
        assertThat(props("kaniko", null).useKaniko()).isTrue();
        // 알 수 없는 값도 호스트 데몬 빌드로 떨어지지 않는다
        assertThat(props("BUILDKIT", null).useKaniko()).isTrue();
        assertThat(props("BUILDX", null).useKaniko()).isFalse();
        assertThat(props(" buildx ", null).useKaniko()).isFalse();
    }

    @Test
    void nativeBuildIsOffUnlessExplicitlyAllowed() {
        assertThat(props("BUILDX", null).allowNativeBuildFallback()).isFalse();
        assertThat(props("BUILDX", false).allowNativeBuildFallback()).isFalse();
        assertThat(props("BUILDX", true).allowNativeBuildFallback()).isTrue();
    }

    @Test
    void kanikoOnAmd64() {
        assertThat(ContainerImageBuilder.resolveMode(true, false, false, true, "amd64")).isEqualTo(Mode.KANIKO);
    }

    @Test
    void kanikoRefusesANonAmd64ControlPlaneInsteadOfShippingTheWrongArchitecture() {
        assertThatThrownBy(() -> ContainerImageBuilder.resolveMode(true, true, true, false, "aarch64"))
                .isInstanceOf(BackendBuildException.class)
                .hasMessageContaining("aarch64")
                .hasMessageContaining("build-isolation=BUILDX");
    }

    @Test
    void buildxIsUsedWhenExplicitlyChosenAndInstalled() {
        assertThat(ContainerImageBuilder.resolveMode(false, false, true, false, "aarch64")).isEqualTo(Mode.BUILDX);
        assertThat(ContainerImageBuilder.resolveMode(false, false, true, true, "amd64")).isEqualTo(Mode.BUILDX);
    }

    @Test
    void withoutBuildxTheHostDockerBuildNeedsAnExplicitAllowance() {
        // 운영에서 실제로 탄 경로: BUILDX 기본값 + buildx 없음 + amd64 → 예전에는 조용히 호스트 docker build
        assertThatThrownBy(() -> ContainerImageBuilder.resolveMode(false, false, false, true, "amd64"))
                .isInstanceOf(BackendBuildException.class)
                .hasMessageContaining("allow-native-build=true");
        assertThat(ContainerImageBuilder.resolveMode(false, true, false, true, "amd64")).isEqualTo(Mode.NATIVE);
    }

    @Test
    void withoutBuildxANonAmd64ControlPlaneCannotBuild() {
        assertThatThrownBy(() -> ContainerImageBuilder.resolveMode(false, true, false, false, "aarch64"))
                .isInstanceOf(BackendBuildException.class)
                .hasMessageContaining("docker-buildx-plugin");
    }

    @Test
    void theKanikoImageIsPinnedByDigest() {
        assertThat(ContainerImageBuilder.KANIKO_IMAGE)
                .startsWith("ghcr.io/osscontainertools/kaniko@sha256:")
                .doesNotContain(":latest");
    }

    private static Ec2ProvisioningProperties props(String isolation, Boolean allowNative) {
        return new Ec2ProvisioningProperties(null, null, null, isolation, null, allowNative);
    }
}
