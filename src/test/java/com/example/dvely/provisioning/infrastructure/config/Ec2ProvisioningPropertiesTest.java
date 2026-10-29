package com.example.dvely.provisioning.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Ec2ProvisioningPropertiesTest {

    @Test
    void buildsOnTheControlPlaneUnlessTargetIsNamed() {
        assertThat(props(null).buildOnTarget()).isFalse();
        assertThat(props("").buildOnTarget()).isFalse();
        assertThat(props("CONTROL_PLANE").buildOnTarget()).isFalse();
        assertThat(props("TARGET").buildOnTarget()).isTrue();
        assertThat(props(" target ").buildOnTarget()).isTrue();
    }

    @Test
    void aMistypedLocationStopsStartupInsteadOfSilentlyBuildingSomewhere() {
        assertThatThrownBy(() -> props("TARGETS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image-build-location");
    }

    private static Ec2ProvisioningProperties props(String location) {
        return new Ec2ProvisioningProperties(null, null, null, null, null, null, location);
    }
}
