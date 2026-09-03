package com.example.dvely.domainbinding.infrastructure.hosting;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도메인을 사용자 AWS 백엔드(EC2)로 연결하는 어댑터. 프론트(GitHub Pages)와 달리 대상이 표준포트+HTTPS
 * 관리형 호스트가 아니라 EC2 라, 도메인은 CNAME 이 아니라 <b>A 레코드로 EIP(안정 IP)</b>를 가리킨다
 * (관리형 서브도메인 경로에서 커맨드서비스가 A 레코드를 만든다).
 *
 * <p>HTTPS 는 인스턴스의 <b>Caddy on-demand TLS</b> 가 종단한다(443 https, 80→443 리다이렉트, 인증서는
 * 첫 요청 때 Let's Encrypt 로 자동 발급 — 남용 방지 ask 게이트가 등록된 도메인만 허용). Caddy 설치는
 * best-effort 라 "붙었으면 HTTPS"라고 단정하지 않고, {@link #verify}에서 실제 https 응답을 프로브해
 * {@code httpsEnforced} 를 실상대로 채운다.</p>
 */
@Component
@RequiredArgsConstructor
public class AwsDomainHostingAdapter implements DomainHostingAdapter {

    private final BackendAddressPort backendAddressPort;
    private final HttpsProbePort tlsProbePort;

    @Override
    public DomainHostingTarget target() {
        return DomainHostingTarget.AWS;
    }

    @Override
    public String resolveDnsTarget(Context context) {
        // 도메인이 A 레코드로 가리킬 대상 = 이 프로젝트의 RUNNING 백엔드 EIP. 없으면 연결 불가.
        return backendAddressPort.resolveRunningBackendIp(context.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "실행 중인 백엔드 서버가 없습니다. 서버를 먼저 배포·기동한 뒤 도메인을 연결하세요."));
    }

    @Override
    public void bind(Context context, String hostname) {
        // 생 EC2 는 자기 도메인을 알 필요가 없다. Cloudflare 레코드는 커맨드서비스가 만든다.
        // 프론트↔백엔드 CORS(허용 오리진 주입)는 별도 단계(P4).
    }

    @Override
    public VerificationStatus verify(Context context, String hostname) {
        boolean backendRunning = backendAddressPort.resolveRunningBackendIp(context.projectId()).isPresent();
        // HTTPS 는 Caddy on-demand 로 자동이지만 설치가 best-effort 라, 실제 https 응답을 프로브해
        // httpsEnforced 를 실상대로 채운다(하드코딩 X). https 가 유효 인증서로 뜨면 인증서도 ACTIVE.
        boolean httpsServing = tlsProbePort.isHttpsServing(hostname);
        CertificateStatus certificateStatus = httpsServing
                ? CertificateStatus.ACTIVE
                : CertificateStatus.PENDING;
        return new VerificationStatus(backendRunning, httpsServing, certificateStatus, null);
    }

    @Override
    public void unbind(Context context, String hostname) {
        // Cloudflare 레코드 삭제는 커맨드서비스(deleteDomain)가 관리형에 대해 처리한다 — 여기선 no-op.
    }
}
