package com.example.dvely.domainbinding.infrastructure.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter.Context;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AwsEc2FrontendDomainHostingAdapterTest {

    @Mock private BackendAddressPort backendAddressPort;
    @Mock private HttpsProbePort tlsProbePort;
    @InjectMocks private AwsEc2FrontendDomainHostingAdapter adapter;

    private final Context ctx = new Context(null, 7L, null, null, null, null);

    @Test
    void targetIsAwsEc2Frontend() {
        assertThat(adapter.target()).isEqualTo(DomainHostingTarget.AWS_EC2_FRONTEND);
    }

    @Test
    void resolveDnsTargetReturnsRunningFrontendEip() {
        // 백엔드가 아니라 프론트(webOnly) 서버의 EIP 를 A 레코드 대상으로 준다.
        when(backendAddressPort.resolveRunningFrontendHost(7L)).thenReturn(Optional.of("54.180.1.2"));
        assertThat(adapter.resolveDnsTarget(ctx)).isEqualTo("54.180.1.2");
    }

    @Test
    void resolveDnsTargetThrowsWhenNoRunningFrontend() {
        when(backendAddressPort.resolveRunningFrontendHost(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.resolveDnsTarget(ctx))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifyMarksHttpsEnforcedAndActiveWhenHttpsProbeSucceeds() {
        // 프론트 EC2 의 Caddy on-demand 로 https 가 실제로 뜬 상태: 프로브 성공 → httpsEnforced·ACTIVE.
        when(backendAddressPort.resolveRunningFrontendHost(7L)).thenReturn(Optional.of("54.180.1.2"));
        when(tlsProbePort.isHttpsServing("app.qeploy.com")).thenReturn(true);
        var vs = adapter.verify(ctx, "app.qeploy.com");
        assertThat(vs.domainConfigured()).isTrue();
        assertThat(vs.httpsEnforced()).isTrue();
        assertThat(vs.certificateStatus()).isEqualTo(CertificateStatus.ACTIVE);
    }

    @Test
    void verifyLeavesHttpsPendingWhenProbeFails() {
        // 아직 DNS 미연결·Caddy 미기동·인증서 미발급: 프로브 실패 → httpsEnforced=false, PENDING.
        when(backendAddressPort.resolveRunningFrontendHost(7L)).thenReturn(Optional.of("54.180.1.2"));
        when(tlsProbePort.isHttpsServing("app.qeploy.com")).thenReturn(false);
        var vs = adapter.verify(ctx, "app.qeploy.com");
        assertThat(vs.domainConfigured()).isTrue();             // 프론트 서버는 떠 있음
        assertThat(vs.httpsEnforced()).isFalse();               // 아직 https 미적용
        assertThat(vs.certificateStatus()).isEqualTo(CertificateStatus.PENDING);
    }
}
