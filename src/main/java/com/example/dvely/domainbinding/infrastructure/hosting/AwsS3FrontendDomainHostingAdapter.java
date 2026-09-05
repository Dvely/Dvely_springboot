package com.example.dvely.domainbinding.infrastructure.hosting;

import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S3 정적 프론트의 HTTPS(CloudFront + ACM) 도메인 어댑터. 실제 프로비저닝(인증서·배포·CNAME)은 비동기라
 * {@code DomainBindingCommandService} 가 {@code S3CdnProvisioningPort} 로 시작하고 {@code S3CdnProvisionWorker}
 * 가 진행한다 — 이 어댑터는 다른 타깃과 인터페이스 파리티를 맞추는 얇은 껍데기다.
 *
 * <ul>
 *   <li>{@link #verify}: 실제 https 응답을 프로브해 CloudFront 가 그 도메인을 https 로 서빙하는지 확인
 *       (워커·수동 검증이 CONNECTED 승격 판단에 쓴다).</li>
 *   <li>{@link #resolveDnsTarget}: S3 경로는 배포 생성 후에야 대상(CloudFront 도메인)을 알아 커맨드서비스가
 *       워커에서 CNAME 을 건다 — 이 어댑터를 통하지 않는다. 호출되면 오용이므로 예외.</li>
 *   <li>bind/unbind: no-op(Cloudflare·AWS 자원은 커맨드서비스·워커·리퍼가 관리).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AwsS3FrontendDomainHostingAdapter implements DomainHostingAdapter {

    private final HttpsProbePort tlsProbePort;

    @Override
    public DomainHostingTarget target() {
        return DomainHostingTarget.AWS_S3_FRONTEND;
    }

    @Override
    public String resolveDnsTarget(Context context) {
        throw new UnsupportedOperationException(
                "S3 프론트 도메인 대상(CloudFront)은 배포 생성 후 워커가 설정합니다.");
    }

    @Override
    public void bind(Context context, String hostname) {
        // CloudFront·ACM·Cloudflare 자원은 커맨드서비스·워커·리퍼가 관리한다.
    }

    @Override
    public VerificationStatus verify(Context context, String hostname) {
        boolean httpsServing = tlsProbePort.isHttpsServing(hostname);
        CertificateStatus certificateStatus = httpsServing
                ? CertificateStatus.ACTIVE
                : CertificateStatus.PENDING;
        return new VerificationStatus(httpsServing, httpsServing, certificateStatus, null);
    }

    @Override
    public void unbind(Context context, String hostname) {
        // Cloudflare 레코드 삭제·CloudFront 정리는 커맨드서비스(deleteDomain)·리퍼가 처리한다.
    }
}
