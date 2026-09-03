package com.example.dvely.domainbinding.application.query;

import static org.assertj.core.api.Assertions.assertThat;
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
    void isBackendDomainRegistered_trueOnlyForRegisteredAwsDomain() {
        DomainBindingQueryService service = new DomainBindingQueryService(
                projectRepository,
                domainBindingRepository,
                new CloudflareProperties(null, null, "qeploy.com", null, null, null, null)
        );
        when(domainBindingRepository.existsByHostnameIgnoreCaseAndHostingTarget(
                "api.example.com", DomainHostingTarget.AWS)).thenReturn(true);

        assertThat(service.isBackendDomainRegistered("api.example.com")).isTrue();
        assertThat(service.isBackendDomainRegistered("  ")).isFalse();   // 공백 → repo 호출 없이 false
        assertThat(service.isBackendDomainRegistered(null)).isFalse();
    }
}