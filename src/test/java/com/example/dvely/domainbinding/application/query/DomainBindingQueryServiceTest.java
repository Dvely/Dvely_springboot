package com.example.dvely.domainbinding.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.infrastructure.config.CloudflareProperties;
import com.example.dvely.project.domain.repository.ProjectRepository;
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

    @Test
    void searchReturnsOnlyActuallySupportedManagedSubdomain() {
        DomainBindingQueryService service = new DomainBindingQueryService(
                projectRepository,
                domainBindingRepository,
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null)
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
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null)
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
}