package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UserContainerRegistryTest {

    @Test
    void resolvesQeployUserIdLabelFirst() {
        String userId = UserContainerRegistry.resolveUserIdLabel(Map.of(
                "qeploy.userId", "11",
                "dvely.userId", "22"
        ));

        assertThat(userId).isEqualTo("11");
    }

    @Test
    void resolvesLegacyDvelyUserIdLabel() {
        String userId = UserContainerRegistry.resolveUserIdLabel(Map.of("dvely.userId", "22"));

        assertThat(userId).isEqualTo("22");
    }
}
