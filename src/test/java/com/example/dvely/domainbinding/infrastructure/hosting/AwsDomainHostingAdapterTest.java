package com.example.dvely.domainbinding.infrastructure.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
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
    void verifyReflectsBackendRunningAndHttpMvp() {
        when(backendAddressPort.resolveRunningBackendIp(7L)).thenReturn(Optional.of("43.202.161.35"));
        var vs = adapter.verify(ctx, "test.qeploy.com");
        assertThat(vs.domainConfigured()).isTrue();
        assertThat(vs.httpsEnforced()).isFalse();               // DNS-only http MVP
        assertThat(vs.certificateStatus()).isEqualTo(CertificateStatus.PENDING);
    }
}
