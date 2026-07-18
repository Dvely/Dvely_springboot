package com.example.dvely.agent.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class InfraOperationTest {

    // The catalog's classification IS the safety/approval policy (design D3/D4) — every constant
    // is checked exhaustively rather than spot-checked, so a future addition/typo in the enum body
    // fails this test instead of silently shipping an under- or over-approved operation.
    private static final Map<InfraOperation, boolean[]> EXPECTED = new EnumMap<>(InfraOperation.class);
    static {
        //                                              serviceImpact costImpact supported approvalRequired
        EXPECTED.put(InfraOperation.STATUS_CHECK,       new boolean[]{false, false, true,  false});
        EXPECTED.put(InfraOperation.LOG_VIEW,            new boolean[]{false, false, true,  false});
        EXPECTED.put(InfraOperation.FAILURE_ANALYSIS,    new boolean[]{false, false, true,  false});
        EXPECTED.put(InfraOperation.RESTART,             new boolean[]{true,  false, true,  true});
        EXPECTED.put(InfraOperation.RESOURCE_SCALING,    new boolean[]{true,  true,  false, false});
        EXPECTED.put(InfraOperation.AUTOSCALING_CHANGE,  new boolean[]{false, true,  false, false});
        EXPECTED.put(InfraOperation.RESOURCE_CLEANUP,    new boolean[]{true,  false, false, false});
    }

    @ParameterizedTest
    @EnumSource(InfraOperation.class)
    void classificationMatchesCatalog(InfraOperation operation) {
        boolean[] expected = EXPECTED.get(operation);
        assertThat(operation.isServiceImpact()).as("%s serviceImpact", operation).isEqualTo(expected[0]);
        assertThat(operation.isCostImpact()).as("%s costImpact", operation).isEqualTo(expected[1]);
        assertThat(operation.isSupported()).as("%s supported", operation).isEqualTo(expected[2]);
        assertThat(operation.approvalRequired()).as("%s approvalRequired", operation).isEqualTo(expected[3]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status_check", "  RESTART  ", "log_view"})
    void parseIsCaseAndWhitespaceTolerant(String raw) {
        assertThat(InfraOperation.parse(raw)).isPresent();
    }

    @Test
    void parseReturnsEmptyForNull() {
        assertThat(InfraOperation.parse(null)).isEqualTo(Optional.empty());
    }

    @Test
    void parseReturnsEmptyForBlank() {
        assertThat(InfraOperation.parse("   ")).isEqualTo(Optional.empty());
    }

    @Test
    void parseReturnsEmptyForUnknownValue() {
        // The whitelist boundary itself (design D3): any string that isn't one of the 7 constants
        // above must resolve to "no operation identified", never fall through to an execution path.
        assertThat(InfraOperation.parse("DELETE_EVERYTHING")).isEqualTo(Optional.empty());
    }

    @Test
    void impactMarkersCombineBothWhenBothImpactsApply() {
        assertThat(InfraOperation.RESOURCE_SCALING.impactMarkers()).isEqualTo("[서비스 영향] [비용 증가 가능] ");
    }

    @Test
    void impactMarkersShowServiceImpactOnly() {
        assertThat(InfraOperation.RESTART.impactMarkers()).isEqualTo("[서비스 영향] ");
    }

    @Test
    void impactMarkersShowCostImpactOnly() {
        assertThat(InfraOperation.AUTOSCALING_CHANGE.impactMarkers()).isEqualTo("[비용 증가 가능] ");
    }

    @Test
    void impactMarkersEmptyWhenNoImpact() {
        assertThat(InfraOperation.STATUS_CHECK.impactMarkers()).isEmpty();
    }
}
