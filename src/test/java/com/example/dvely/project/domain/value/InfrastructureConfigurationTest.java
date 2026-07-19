package com.example.dvely.project.domain.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InfrastructureConfigurationTest {

    @Test
    void parseAcceptsEveryValidFieldCombination() {
        InfrastructureConfiguration configuration =
                InfrastructureConfiguration.parse("CONTAINER", "SMALL", "OBJECT_STORAGE", "PUBLIC");

        assertThat(configuration.deploymentArchitecture()).isEqualTo(DeploymentArchitecture.CONTAINER);
        assertThat(configuration.computeTier()).isEqualTo(ComputeTier.SMALL);
        assertThat(configuration.storageType()).isEqualTo(StorageType.OBJECT_STORAGE);
        assertThat(configuration.networkAccess()).isEqualTo(NetworkAccess.PUBLIC);
    }

    @Test
    void parseRejectsUnknownDeploymentArchitectureWithFieldNamedMessage() {
        assertThatThrownBy(() -> InfrastructureConfiguration.parse("SERVERLESS2", "SMALL", "NONE", "PUBLIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deploymentArchitecture")
                .hasMessageContaining("SERVERLESS2");
    }

    @Test
    void parseRejectsUnknownComputeTierWithFieldNamedMessage() {
        assertThatThrownBy(() -> InfrastructureConfiguration.parse("SERVER", "HUGE", "NONE", "PUBLIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("computeTier")
                .hasMessageContaining("HUGE");
    }

    @Test
    void parseRejectsUnknownStorageTypeWithFieldNamedMessage() {
        assertThatThrownBy(() -> InfrastructureConfiguration.parse("SERVER", "SMALL", "BLOCK_STORAGE", "PUBLIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storageType")
                .hasMessageContaining("BLOCK_STORAGE");
    }

    @Test
    void parseRejectsUnknownNetworkAccessWithFieldNamedMessage() {
        assertThatThrownBy(() -> InfrastructureConfiguration.parse("SERVER", "SMALL", "NONE", "INTERNAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkAccess")
                .hasMessageContaining("INTERNAL");
    }

    @Test
    void compactConstructorRejectsNullFields() {
        assertThatThrownBy(() -> new InfrastructureConfiguration(
                null, ComputeTier.SMALL, StorageType.NONE, NetworkAccess.PUBLIC
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, null, StorageType.NONE, NetworkAccess.PUBLIC
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.SMALL, null, NetworkAccess.PUBLIC
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new InfrastructureConfiguration(
                DeploymentArchitecture.SERVER, ComputeTier.SMALL, StorageType.NONE, null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsTreatsSameFourValuesAsEqual_backingTheNoOpPutDetection() {
        InfrastructureConfiguration first =
                new InfrastructureConfiguration(DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC);
        InfrastructureConfiguration second =
                new InfrastructureConfiguration(DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC);
        InfrastructureConfiguration different =
                new InfrastructureConfiguration(DeploymentArchitecture.CONTAINER, ComputeTier.LARGE, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void summaryTextIncludesAllFourFieldsInKoreanLabelFormat() {
        InfrastructureConfiguration configuration =
                new InfrastructureConfiguration(DeploymentArchitecture.CONTAINER, ComputeTier.SMALL, StorageType.OBJECT_STORAGE, NetworkAccess.PUBLIC);

        assertThat(configuration.summaryText())
                .isEqualTo("아키텍처=CONTAINER, 컴퓨팅=SMALL, 스토리지=OBJECT_STORAGE, 네트워크=PUBLIC");
    }
}
