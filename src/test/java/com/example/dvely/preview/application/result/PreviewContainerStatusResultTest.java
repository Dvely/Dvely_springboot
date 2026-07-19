package com.example.dvely.preview.application.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.ContainerResourceUsage;
import com.example.dvely.agent.infrastructure.docker.ContainerRuntimeStatus;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * review F6: the prior test suite only exercised the "resources==null" branch of
 * {@link PreviewContainerStatusResult#of}, never the actual memory%/CPU% derivation
 * (usage/limit*100 and the 1-decimal rounding) on a normal, populated sample.
 */
class PreviewContainerStatusResultTest {

    @Test
    void computesMemoryUsagePercentAndRoundsCpuPercentToOneDecimalOnHappyPath() {
        PreviewSessionEntity session = session();
        ContainerRuntimeStatus runtimeStatus = new ContainerRuntimeStatus(true, false, null, LocalDateTime.now());
        // 256 MiB / 1 GiB = 25.0% exactly; cpuPercent given with more precision than the
        // 1-decimal display contract to prove rounding actually happens, not just pass-through.
        ContainerResourceUsage usage = new ContainerResourceUsage(268_435_456L, 1_073_741_824L, 12.34);

        PreviewContainerStatusResult result = PreviewContainerStatusResult.of(session, runtimeStatus, usage);

        assertThat(result.resources()).isNotNull();
        assertThat(result.resources().memoryUsageBytes()).isEqualTo(268_435_456L);
        assertThat(result.resources().memoryLimitBytes()).isEqualTo(1_073_741_824L);
        assertThat(result.resources().memoryUsagePercent()).isEqualTo(25.0);
        assertThat(result.resources().cpuPercent()).isEqualTo(12.3);
    }

    @Test
    void roundsCpuPercentHalfUpToOneDecimal() {
        PreviewSessionEntity session = session();
        ContainerRuntimeStatus runtimeStatus = new ContainerRuntimeStatus(true, false, null, LocalDateTime.now());
        ContainerResourceUsage usage = new ContainerResourceUsage(0L, 1_073_741_824L, 5.55);

        PreviewContainerStatusResult result = PreviewContainerStatusResult.of(session, runtimeStatus, usage);

        assertThat(result.resources().cpuPercent()).isEqualTo(5.6);
    }

    private PreviewSessionEntity session() {
        return new PreviewSessionEntity(
                "session-1",
                "token",
                1L,
                11L,
                21L,
                "task-1",
                "container-1",
                32768,
                "https://preview.qeploy.test/session-1/",
                LocalDateTime.now().plusMinutes(30)
        );
    }
}
