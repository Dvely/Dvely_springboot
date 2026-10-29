package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildStatusReportTest {

    @Test
    void readsTheStateLineAndTheLogTailAfterAFailure() {
        assertThat(BuildStatusReport.parse("BUILDING")).hasValueSatisfying(status -> {
            assertThat(status.state()).isEqualTo(BuildStatusReport.State.BUILDING);
            assertThat(status.logTail()).isNull();
        });
        assertThat(BuildStatusReport.parse("BUILD_FAILED\nStep 3/5 : RUN npm ci\nnpm ERR! boom"))
                .hasValueSatisfying(status -> {
                    assertThat(status.state()).isEqualTo(BuildStatusReport.State.BUILD_FAILED);
                    assertThat(status.logTail()).isEqualTo("Step 3/5 : RUN npm ci\nnpm ERR! boom");
                });
    }

    @Test
    void anUnknownValueIsNotMistakenForAState() {
        assertThat(BuildStatusReport.parse("10.0.1.23")).isEmpty();
        assertThat(BuildStatusReport.parse("building")).isEmpty();
        assertThat(BuildStatusReport.parse(null)).isEmpty();
    }

    @Test
    void theStatusPathStaysOutOfTheProjectEnvPath() {
        // /qeploy/{projectId}/ 아래는 부팅 스크립트가 통째로 앱 env 로 내린다
        assertThat(BuildStatusReport.path(42L, "i-abc")).isEqualTo("/qeploy/build-status/42/i-abc");
        assertThat(BuildStatusReport.path(42L, "i-abc")).doesNotStartWith("/qeploy/42/");
    }
}
