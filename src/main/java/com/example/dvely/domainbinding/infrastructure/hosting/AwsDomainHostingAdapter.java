package com.example.dvely.domainbinding.infrastructure.hosting;

import com.example.dvely.domainbinding.application.port.out.BackendAddressPort;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도메인을 사용자 AWS 백엔드(EC2)로 연결하는 어댑터. 프론트(GitHub Pages)와 달리 대상이 표준포트+HTTPS
 * 관리형 호스트가 아니라 8080 http 생 EC2 라, 도메인은 CNAME 이 아니라 <b>A 레코드로 EIP(안정 IP)</b>를
 * 가리킨다(관리형 서브도메인 경로에서 커맨드서비스가 A 레코드를 만든다).
 *
 * <p>MVP 는 DNS-only(프록시 off, http://label.qeploy.com:8080). HTTPS 는 후속(B) — Cloudflare 프록시
 * +Origin Rule 로 443→8080 을 감싼다.</p>
 */
@Component
@RequiredArgsConstructor
public class AwsDomainHostingAdapter implements DomainHostingAdapter {

    private final BackendAddressPort backendAddressPort;

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
        // DNS-only http MVP: HTTPS 미적용(B 에서 Cloudflare 프록시+Origin Rule) → cert 는 PENDING.
        return new VerificationStatus(backendRunning, false, CertificateStatus.PENDING, null);
    }

    @Override
    public void unbind(Context context, String hostname) {
        // Cloudflare 레코드 삭제는 커맨드서비스(deleteDomain)가 관리형에 대해 처리한다 — 여기선 no-op.
    }
}
