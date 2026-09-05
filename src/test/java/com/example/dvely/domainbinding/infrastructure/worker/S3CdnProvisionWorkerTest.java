package com.example.dvely.domainbinding.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort.AcmCertStatus;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort.CdnDistribution;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class S3CdnProvisionWorkerTest {

    private static final String HOST = "s3app.qeploy.com";
    private static final String CERT = "arn:aws:acm:us-east-1:123:certificate/abc";

    @Mock private DomainBindingRepository domainBindingRepository;
    @Mock private S3CdnProvisioningPort cdnProvisioningPort;
    @Mock private CloudflareDnsPort cloudflareDnsPort;
    @Mock private HttpsProbePort httpsProbePort;
    @InjectMocks private S3CdnProvisionWorker worker;

    private DomainBinding provisioningDomain() {
        DomainBinding domain = new DomainBinding(11L, DomainType.MANAGED_SUBDOMAIN,
                DomainHostingTarget.AWS_S3_FRONTEND, HOST, DomainStatus.PROVISIONING,
                VerificationMethod.CNAME, null);
        domain.assignAcmCertificate(CERT);
        return domain;
    }

    private DomainBinding customProvisioningDomain() {
        DomainBinding domain = new DomainBinding(11L, DomainType.CUSTOM_DOMAIN,
                DomainHostingTarget.AWS_S3_FRONTEND, "www.mysite.com", DomainStatus.PROVISIONING,
                VerificationMethod.CNAME, null);
        domain.assignAcmCertificate(CERT);
        return domain;
    }

    private void givenPending(DomainBinding domain) {
        when(domainBindingRepository.findByStatus(eq(DomainStatus.PROVISIONING), anyInt()))
                .thenReturn(List.of(domain));
    }

    @Test
    void addsAcmValidationCname_whenCertPendingAndRecordAvailable() {
        DomainBinding domain = provisioningDomain();
        givenPending(domain);
        when(cdnProvisioningPort.describeCertificate(11L, CERT)).thenReturn(new AcmCertStatus(
                "PENDING_VALIDATION", "_x.s3app.qeploy.com.", "_y.acm-validations.aws.",
                false, false, true));
        when(cloudflareDnsPort.createCnameRecord("_x.s3app.qeploy.com", "_y.acm-validations.aws", false))
                .thenReturn("rec-val");

        worker.pollProvisioning();

        // 검증 CNAME 은 우리 존에 proxied=false 로, FQDN 끝점 제거해서 등록한다.
        verify(cloudflareDnsPort).createCnameRecord("_x.s3app.qeploy.com", "_y.acm-validations.aws", false);
        assertThat(domain.getAcmValidationRecordId()).isEqualTo("rec-val");
        assertThat(domain.getStatus()).isEqualTo(DomainStatus.PROVISIONING);   // 아직 진행 중
    }

    @Test
    void createsDistributionAndFinalCname_whenCertIssued() {
        DomainBinding domain = provisioningDomain();
        domain.assignAcmValidationRecord("rec-val");   // 검증 CNAME 은 이미 등록됨
        givenPending(domain);
        when(cdnProvisioningPort.describeCertificate(11L, CERT)).thenReturn(new AcmCertStatus(
                "ISSUED", null, null, true, false, false));
        when(cdnProvisioningPort.createDistribution(11L, HOST, CERT))
                .thenReturn(new CdnDistribution("E123", "d123.cloudfront.net"));
        when(cloudflareDnsPort.createCnameRecord(HOST, "d123.cloudfront.net", false))
                .thenReturn("rec-final");

        worker.pollProvisioning();

        verify(cdnProvisioningPort).createDistribution(11L, HOST, CERT);
        // 도메인→CloudFront 최종 CNAME 은 proxied=false(CloudFront 가 우리 도메인 ACM 인증서로 직접 서빙).
        verify(cloudflareDnsPort).createCnameRecord(HOST, "d123.cloudfront.net", false);
        assertThat(domain.getCloudfrontDistributionId()).isEqualTo("E123");
        assertThat(domain.getDnsTarget()).isEqualTo("d123.cloudfront.net");
        assertThat(domain.getStatus()).isEqualTo(DomainStatus.PROVISIONING);   // Deployed·https 확인은 이후
    }

    @Test
    void promotesToConnected_whenHttpsServing() {
        DomainBinding domain = provisioningDomain();
        domain.assignCloudfrontDistribution("E123", "d123.cloudfront.net", "rec-final");
        givenPending(domain);
        when(httpsProbePort.isHttpsServing(HOST)).thenReturn(true);

        worker.pollProvisioning();

        assertThat(domain.getStatus()).isEqualTo(DomainStatus.CONNECTED);
        assertThat(domain.isHttpsEnforced()).isTrue();
        // 배포가 생겼으면 인증서 describe 는 더 안 본다(https 프로브만).
        verify(cdnProvisioningPort, never()).describeCertificate(anyLong(), any());
    }

    @Test
    void staysProvisioning_whenHttpsNotYetServing() {
        DomainBinding domain = provisioningDomain();
        domain.assignCloudfrontDistribution("E123", "d123.cloudfront.net", "rec-final");
        givenPending(domain);
        when(httpsProbePort.isHttpsServing(HOST)).thenReturn(false);

        worker.pollProvisioning();

        assertThat(domain.getStatus()).isEqualTo(DomainStatus.PROVISIONING);
    }

    @Test
    void custom_certPending_doesNotAddValidationCname_userAddsToTheirDns() {
        DomainBinding domain = customProvisioningDomain();
        givenPending(domain);
        when(cdnProvisioningPort.describeCertificate(11L, CERT)).thenReturn(new AcmCertStatus(
                "PENDING_VALIDATION", "_x.www.mysite.com.", "_y.acm-validations.aws.",
                false, false, true));

        worker.pollProvisioning();

        // 커스텀은 우리 존에 검증 CNAME 을 안 건다 — 사용자가 자기 DNS 에 넣는다(가이드로 안내).
        verify(cloudflareDnsPort, never()).createCnameRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(domain.getAcmValidationRecordId()).isNull();
        assertThat(domain.getStatus()).isEqualTo(DomainStatus.PROVISIONING);
    }

    @Test
    void custom_certIssued_createsDistribution_butNoFinalCname() {
        DomainBinding domain = customProvisioningDomain();
        givenPending(domain);
        when(cdnProvisioningPort.describeCertificate(11L, CERT)).thenReturn(new AcmCertStatus(
                "ISSUED", null, null, true, false, false));
        when(cdnProvisioningPort.createDistribution(11L, "www.mysite.com", CERT))
                .thenReturn(new CdnDistribution("E999", "dcustom.cloudfront.net"));

        worker.pollProvisioning();

        verify(cdnProvisioningPort).createDistribution(11L, "www.mysite.com", CERT);
        // 최종 CNAME 도 사용자 몫 — 우리 존엔 안 건다. dnsTarget 만 남겨 가이드로 안내한다.
        verify(cloudflareDnsPort, never()).createCnameRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(domain.getCloudfrontDistributionId()).isEqualTo("E999");
        assertThat(domain.getDnsTarget()).isEqualTo("dcustom.cloudfront.net");
        assertThat(domain.getCloudflareRecordId()).isNull();   // 우리 CNAME 레코드 없음
    }

    @Test
    void failsDomain_whenCertValidationFailed() {
        DomainBinding domain = provisioningDomain();
        givenPending(domain);
        when(cdnProvisioningPort.describeCertificate(11L, CERT)).thenReturn(new AcmCertStatus(
                "VALIDATION_TIMED_OUT", null, null, false, true, false));

        worker.pollProvisioning();

        assertThat(domain.getStatus()).isEqualTo(DomainStatus.FAILED);
        verify(cdnProvisioningPort, never()).createDistribution(
                anyLong(), any(), any());
    }

}
