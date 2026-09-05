package com.example.dvely.domainbinding.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort.AcmCertStatus;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainBindingQueryServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DomainBindingRepository domainBindingRepository;

    @Mock
    private com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort s3CdnProvisioningPort;

    @Test
    void searchReturnsOnlyActuallySupportedManagedSubdomain() {
        DomainBindingQueryService service = new DomainBindingQueryService(
                projectRepository,
                domainBindingRepository,
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null),
                s3CdnProvisioningPort
        );
        when(domainBindingRepository.existsByHostnameIgnoreCase("sample.qeploy.com"))
                .thenReturn(false);

        var result = service.search("sample");

        assertThat(result.results()).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo(DomainType.MANAGED_SUBDOMAIN);
            assertThat(candidate.hostname()).isEqualTo("sample.qeploy.com");
            assertThat(candidate.available()).isTrue();
        });
    }
    @Test
    void isEc2DomainRegistered_trueForRegisteredBackendOrFrontendEc2Domain() {
        DomainBindingQueryService service = new DomainBindingQueryService(
                projectRepository,
                domainBindingRepository,
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null),
                s3CdnProvisioningPort
        );
        // 미매칭 조회는 기본값(false) — lenient 로 둬 strict stubbing 이 미스텁 호출에 예외를 던지지 않게 한다
        // (isEc2DomainRegistered 는 AWS 를 먼저 물어보고 false 면 AWS_EC2_FRONTEND 를 묻는다).
        // 백엔드(AWS) 도메인 등록됨
        lenient().when(domainBindingRepository.existsByHostnameIgnoreCaseAndHostingTarget(
                "api.example.com", DomainHostingTarget.AWS)).thenReturn(true);
        // 독립 프론트(AWS_EC2_FRONTEND) 도메인 등록됨(AWS 로는 없음)
        lenient().when(domainBindingRepository.existsByHostnameIgnoreCaseAndHostingTarget(
                "www.example.com", DomainHostingTarget.AWS_EC2_FRONTEND)).thenReturn(true);

        assertThat(service.isEc2DomainRegistered("api.example.com")).isTrue();   // 백엔드 Caddy ask 통과
        assertThat(service.isEc2DomainRegistered("www.example.com")).isTrue();   // 프론트 Caddy ask 통과
        assertThat(service.isEc2DomainRegistered("nope.example.com")).isFalse(); // 미등록
        assertThat(service.isEc2DomainRegistered("  ")).isFalse();   // 공백 → repo 호출 없이 false
        assertThat(service.isEc2DomainRegistered(null)).isFalse();
    }

    private DomainBindingQueryService service() {
        return new DomainBindingQueryService(
                projectRepository,
                domainBindingRepository,
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null),
                s3CdnProvisioningPort);
    }

    private DomainBinding customS3Domain() {
        return new DomainBinding(7L, DomainType.CUSTOM_DOMAIN, DomainHostingTarget.AWS_S3_FRONTEND,
                "www.mysite.com", DomainStatus.PROVISIONING, VerificationMethod.CNAME, null);
    }

    @Test
    void s3CustomDomainGuide_phase1_showsAcmValidationCname() {
        // 배포 전(distributionId 없음): ACM DNS 검증 CNAME 을 사용자에게 안내한다(끝점 제거).
        DomainBinding domain = customS3Domain();
        domain.assignAcmCertificate("arn:cert");
        when(domainBindingRepository.findById(99L)).thenReturn(Optional.of(domain));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(7L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));
        when(s3CdnProvisioningPort.describeCertificate(7L, "arn:cert")).thenReturn(new AcmCertStatus(
                "PENDING_VALIDATION", "_x.www.mysite.com.", "_y.acm-validations.aws.",
                false, false, true));

        var guide = service().getVerificationGuide(1L, 99L);

        assertThat(guide.records()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("CNAME");
            assertThat(r.host()).isEqualTo("_x.www.mysite.com");
            assertThat(r.value()).isEqualTo("_y.acm-validations.aws");
        });
    }

    @Test
    void s3CustomDomainGuide_phase2_showsFinalCnameToCloudfront() {
        // 배포 후(distributionId 있음): 도메인 → CloudFront 최종 CNAME 을 안내한다.
        DomainBinding domain = customS3Domain();
        domain.assignAcmCertificate("arn:cert");
        domain.assignCloudfrontDistribution("E999", "dcustom.cloudfront.net", null);
        when(domainBindingRepository.findById(99L)).thenReturn(Optional.of(domain));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(7L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));

        var guide = service().getVerificationGuide(1L, 99L);

        assertThat(guide.records()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("CNAME");
            assertThat(r.host()).isEqualTo("www.mysite.com");
            assertThat(r.value()).isEqualTo("dcustom.cloudfront.net");
        });
        verify(s3CdnProvisioningPort, never()).describeCertificate(anyLong(), any());   // 2단계는 인증서 조회 안 함
    }
}