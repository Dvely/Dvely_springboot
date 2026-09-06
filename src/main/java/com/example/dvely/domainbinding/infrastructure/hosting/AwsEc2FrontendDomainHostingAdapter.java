package com.example.dvely.domainbinding.infrastructure.hosting;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도메인을 사용자 AWS 독립 프론트(웹 전용 EC2)로 연결하는 어댑터. 백엔드({@link AwsDomainHostingAdapter})
 * 와 <b>동일한 EC2 기계</b>(A 레코드로 EIP 지향 + 인스턴스 Caddy on-demand TLS 로 443 HTTPS 종단)를 쓰고,
 * 대상 서버가 webOnly 프론트라는 점만 다르다 — 웹 전용 서버도 배포 시 EIP·Caddy·SG 443/80 을 그대로 상속한다.
 *
 * <p>도메인은 그 프론트 서버의 EIP 를 A 레코드로 가리키고, HTTPS 는 Caddy 가 첫 요청 때 Let's Encrypt 로
 * 자동 발급한다(남용 방지 ask 게이트가 등록된 도메인만 허용 — 커스텀 도메인은 {@code /api/v1/tls/allow}
 * 로 물어 본다). 설치가 best-effort 라 "붙었으면 HTTPS"로 단정하지 않고 {@link #verify}에서 실제 https
 * 응답을 프로브해 {@code httpsEnforced} 를 실상대로 채운다.</p>
 */
@Component
@RequiredArgsConstructor
public class AwsEc2FrontendDomainHostingAdapter implements DomainHostingAdapter {

    private final BackendAddressPort backendAddressPort;
    private final HttpsProbePort tlsProbePort;

    @Override
    public DomainHostingTarget target() {
        return DomainHostingTarget.AWS_EC2_FRONTEND;
    }

    @Override
    public String resolveDnsTarget(Context context) {
        // 도메인이 A 레코드로 가리킬 대상 = 이 프로젝트의 RUNNING 프론트(webOnly) 서버 EIP. 없으면 연결 불가.
        return backendAddressPort.resolveRunningFrontendHost(context.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "실행 중인 프론트 서버가 없습니다. 프론트를 EC2 로 먼저 배포·기동한 뒤 도메인을 연결하세요."));
    }

    @Override
    public void bind(Context context, String hostname) {
        // 생 EC2 는 자기 도메인을 알 필요가 없다. Cloudflare 레코드는 커맨드서비스가 만든다.
    }

    @Override
    public VerificationStatus verify(Context context, String hostname) {
        boolean frontendRunning = backendAddressPort.resolveRunningFrontendHost(context.projectId()).isPresent();
        // HTTPS 는 Caddy on-demand 로 자동이지만 설치가 best-effort 라, 실제 https 응답을 프로브해
        // httpsEnforced 를 실상대로 채운다(하드코딩 X). https 가 유효 인증서로 뜨면 인증서도 ACTIVE.
        boolean httpsServing = tlsProbePort.isHttpsServing(hostname);
        CertificateStatus certificateStatus = httpsServing
                ? CertificateStatus.ACTIVE
                : CertificateStatus.PENDING;
        return new VerificationStatus(frontendRunning, httpsServing, certificateStatus, null);
    }

    @Override
    public void unbind(Context context, String hostname) {
        // Cloudflare 레코드 삭제는 커맨드서비스(deleteDomain)가 관리형에 대해 처리한다 — 여기선 no-op.
    }
}
