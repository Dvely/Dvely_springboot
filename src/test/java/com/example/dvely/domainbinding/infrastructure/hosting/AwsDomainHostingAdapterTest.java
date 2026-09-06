package com.example.dvely.domainbinding.infrastructure.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter.Context;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AwsDomainHostingAdapterTest {

    @Mock private BackendAddressPort backendAddressPort;
    @Mock private HttpsProbePort tlsProbePort;
    @InjectMocks private AwsDomainHostingAdapter adapter;

    private final Context ctx = new Context(null, 7L, null, null, null, null);

    @Test
    void targetIsAws() {
        assertThat(adapter.target()).isEqualTo(DomainHostingTarget.AWS);
    }

    @Test
    void resolveDnsTargetReturnsRunningBackendIp() {
        when(backendAddressPort.resolveRunningBackendIp(7L)).thenReturn(Optional.of("43.202.161.35"));
        assertThat(adapter.resolveDnsTarget(ctx)).isEqualTo("43.202.161.35");
    }

    @Test
    void resolveDnsTargetThrowsWhenNoRunningBackend() {
        when(backendAddressPort.resolveRunningBackendIp(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.resolveDnsTarget(ctx))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifyMarksHttpsEnforcedAndActiveWhenHttpsProbeSucceeds() {
        // Caddy on-demand 로 https 가 실제로 뜬 상태: 프로브 성공 → httpsEnforced·ACTIVE.
        when(backendAddressPort.resolveRunningBackendIp(7L)).thenReturn(Optional.of("43.202.161.35"));
        when(tlsProbePort.isHttpsServing("test.qeploy.com")).thenReturn(true);
        var vs = adapter.verify(ctx, "test.qeploy.com");
        assertThat(vs.domainConfigured()).isTrue();
        assertThat(vs.httpsEnforced()).isTrue();
        assertThat(vs.certificateStatus()).isEqualTo(CertificateStatus.ACTIVE);
    }

    @Test
    void verifyLeavesHttpsPendingWhenProbeFails() {
        // 아직 DNS 미연결·Caddy 미기동·인증서 미발급: 프로브 실패 → httpsEnforced=false, PENDING.
        when(backendAddressPort.resolveRunningBackendIp(7L)).thenReturn(Optional.of("43.202.161.35"));
        when(tlsProbePort.isHttpsServing("test.qeploy.com")).thenReturn(false);
        var vs = adapter.verify(ctx, "test.qeploy.com");
        assertThat(vs.domainConfigured()).isTrue();             // 백엔드는 떠 있음
        assertThat(vs.httpsEnforced()).isFalse();               // 아직 https 미적용
        assertThat(vs.certificateStatus()).isEqualTo(CertificateStatus.PENDING);
    }
}
