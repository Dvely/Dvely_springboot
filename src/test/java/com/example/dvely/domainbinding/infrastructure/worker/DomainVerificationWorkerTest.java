package com.example.dvely.domainbinding.infrastructure.worker;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import com.example.dvely.domainbinding.infrastructure.config.DomainVerificationProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DomainVerificationWorkerTest {

    private DomainBindingRepository domainBindingRepository;
    private DomainBindingCommandService commandService;
    private DomainVerificationWorker worker;

    @BeforeEach
    void setUp() {
        domainBindingRepository = mock(DomainBindingRepository.class);
        commandService = mock(DomainBindingCommandService.class);
        worker = new DomainVerificationWorker(
                domainBindingRepository,
                commandService,
                new DomainVerificationProperties(60000L, 20, 30, 1440)
        );
    }

    @Test
    void verifiesEveryVerifyingDomain() {
        // 이 워커가 없으면 도메인은 영원히 VERIFYING 이다 — CONNECTED 로 가는 길은 이 호출뿐이다.
        givenVerifyingDomains(
                managedSubdomain(1L, "a.qeploy.com", LocalDateTime.now()),
                managedSubdomain(2L, "b.qeploy.com", LocalDateTime.now())
        );
        when(commandService.checkVerificationAsSystem(anyLong())).thenReturn(result(DomainStatus.VERIFYING));

        worker.verifyPendingDomains();

        verify(commandService).checkVerificationAsSystem(1L);
        verify(commandService).checkVerificationAsSystem(2L);
    }

    @Test
    void oneDomainFailingDoesNotAbortTheRestOfTheBatch() {
        // Cloudflare·GitHub 호출이 섞여 있어 어느 하나는 언제든 실패한다. 다음 주기에 다시
        // 시도하면 되는 성격이므로 나머지 도메인까지 막으면 안 된다.
        givenVerifyingDomains(
                managedSubdomain(1L, "a.qeploy.com", LocalDateTime.now()),
                managedSubdomain(2L, "b.qeploy.com", LocalDateTime.now())
        );
        when(commandService.checkVerificationAsSystem(1L)).thenThrow(new IllegalStateException("boom"));
        when(commandService.checkVerificationAsSystem(2L)).thenReturn(result(DomainStatus.CONNECTED));

        worker.verifyPendingDomains();

        verify(commandService).checkVerificationAsSystem(2L);
        verify(commandService, never()).abandonVerification(anyLong());
    }

    @Test
    void managedSubdomainPastItsTtlIsClosedAsFailed() {
        // 우리가 Cloudflare 레코드를 직접 만드는 쪽이라, 30분이 지나도록 안 붙었으면 붙지 않는다.
        givenVerifyingDomains(managedSubdomain(1L, "a.qeploy.com", LocalDateTime.now().minusMinutes(31)));

        worker.verifyPendingDomains();

        verify(commandService).abandonVerification(1L);
        verify(commandService, never()).checkVerificationAsSystem(anyLong());
    }

    @Test
    void customDomainKeepsBeingVerifiedLongAfterAManagedOneWouldHaveBeenGivenUp() {
        // 사용자가 자기 registrar 에서 CNAME 을 거는 것을 기다려야 한다. 관리형과 같은 TTL 을
        // 쓰면 사용자가 손도 대기 전에 FAILED 로 닫힌다.
        givenVerifyingDomains(customDomain(1L, "www.example.com", LocalDateTime.now().minusHours(5)));
        when(commandService.checkVerificationAsSystem(1L)).thenReturn(result(DomainStatus.VERIFYING));

        worker.verifyPendingDomains();

        verify(commandService).checkVerificationAsSystem(1L);
        verify(commandService, never()).abandonVerification(anyLong());
    }

    @Test
    void customDomainPastADayIsClosedAsFailed() {
        givenVerifyingDomains(customDomain(1L, "www.example.com", LocalDateTime.now().minusHours(25)));

        worker.verifyPendingDomains();

        verify(commandService).abandonVerification(1L);
    }

    @Test
    void purchasableDomainsAreSkippedEntirely() {
        // 검증 자체가 미지원이라 태우면 매 주기 예외만 남는다.
        givenVerifyingDomains(domain(1L, DomainType.PURCHASABLE_DOMAIN, "shop.example.com", LocalDateTime.now()));

        worker.verifyPendingDomains();

        verify(commandService, never()).checkVerificationAsSystem(anyLong());
        verify(commandService, never()).abandonVerification(anyLong());
    }

    private void givenVerifyingDomains(DomainBinding... domains) {
        when(domainBindingRepository.findByStatus(DomainStatus.VERIFYING, 20))
                .thenReturn(List.of(domains));
    }

    private DomainBinding managedSubdomain(Long id, String hostname, LocalDateTime createdAt) {
        return domain(id, DomainType.MANAGED_SUBDOMAIN, hostname, createdAt);
    }

    private DomainBinding customDomain(Long id, String hostname, LocalDateTime createdAt) {
        return domain(id, DomainType.CUSTOM_DOMAIN, hostname, createdAt);
    }

    private DomainBinding domain(Long id, DomainType type, String hostname, LocalDateTime createdAt) {
        return new DomainBinding(
                id,
                11L,
                type,
                DomainHostingTarget.GITHUB_PAGES,
                hostname,
                DomainStatus.VERIFYING,
                VerificationMethod.CNAME,
                "octo.github.io",
                "record-1",
                false,
                CertificateStatus.PENDING,
                null,
                null,
                createdAt,
                createdAt
        );
    }

    private DomainBindingResult result(DomainStatus status) {
        return new DomainBindingResult(
                1L,
                11L,
                DomainType.MANAGED_SUBDOMAIN,
                DomainHostingTarget.GITHUB_PAGES,
                "a.qeploy.com",
                status,
                VerificationMethod.CNAME,
                "octo.github.io",
                false,
                CertificateStatus.PENDING,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
