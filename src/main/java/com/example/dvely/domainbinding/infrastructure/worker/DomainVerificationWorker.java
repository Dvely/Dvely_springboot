package com.example.dvely.domainbinding.infrastructure.worker;

import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.infrastructure.config.DomainVerificationProperties;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * VERIFYING 도메인을 주기적으로 검증한다.
 *
 * 이것이 없으면 도메인은 영원히 VERIFYING 에 머문다. VERIFYING 에서 CONNECTED 로 가는 길은
 * checkVerification 호출 하나뿐인데 그것을 밟는 주체가 없었다. 그러면 domainSummary.url 이
 * 영원히 비어 있어서, DNS 도 호스팅 설정도 이미 끝나 주소를 직접 치면 열리는 도메인의 링크를
 * 사용자가 화면에서 못 본다.
 *
 * 인증서는 종료 조건에 넣지 않는다. 관리형 서브도메인은 Cloudflare 프록시 뒤에 있어 GitHub
 * Pages 가 인증서를 발급하지 못하고, certificateStatus 는 영원히 ACTIVE 가 되지 않는다.
 * 그것을 기다리면 검증이 끝나지 않는다. 연결 판정은 markVerificationChecked 가 이미
 * dnsConnected 와 domainConfigured 만 보고 내린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainVerificationWorker {

    private final DomainBindingRepository domainBindingRepository;
    private final DomainBindingCommandService domainBindingCommandService;
    private final DomainVerificationProperties properties;

    @Scheduled(fixedDelayString = "${qeploy.domain-binding.verification.poll-interval-ms:60000}")
    public void verifyPendingDomains() {
        for (DomainBinding domain : domainBindingRepository.findByStatus(
                DomainStatus.VERIFYING, properties.batchSizeOrDefault())) {
            // 구매형은 검증 자체가 미지원이라 태우면 매 주기 예외만 남는다.
            if (domain.getType() == DomainType.PURCHASABLE_DOMAIN) {
                continue;
            }
            if (isExpired(domain)) {
                abandon(domain);
                continue;
            }
            verify(domain);
        }
    }

    private void verify(DomainBinding domain) {
        try {
            // 한 도메인의 실패가 나머지를 막지 않아야 한다. Cloudflare·GitHub 호출이 섞여 있어
            // 어느 하나는 언제든 실패할 수 있고, 다음 주기에 다시 시도하면 되는 성격이다.
            DomainStatus status = domainBindingCommandService
                    .checkVerificationAsSystem(domain.getId())
                    .status();
            if (status == DomainStatus.CONNECTED) {
                log.info("도메인 검증 완료: domainId={} hostname={}", domain.getId(), domain.getHostname());
            }
        } catch (RuntimeException exception) {
            log.warn("도메인 검증 실패 — 다음 주기에 재시도: domainId={} hostname={} 원인={}",
                    domain.getId(), domain.getHostname(), exception.toString());
        }
    }

    private void abandon(DomainBinding domain) {
        try {
            domainBindingCommandService.abandonVerification(domain.getId());
            log.warn("도메인 검증 포기 — FAILED 로 닫는다: domainId={} hostname={} type={} createdAt={}",
                    domain.getId(), domain.getHostname(), domain.getType(), domain.getCreatedAt());
        } catch (RuntimeException exception) {
            log.warn("도메인 검증 포기 처리 실패: domainId={} 원인={}", domain.getId(), exception.toString());
        }
    }

    private boolean isExpired(DomainBinding domain) {
        LocalDateTime createdAt = domain.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        int ttlMinutes = domain.getType() == DomainType.MANAGED_SUBDOMAIN
                ? properties.managedTtlMinutesOrDefault()
                : properties.customTtlMinutesOrDefault();
        return createdAt.plusMinutes(ttlMinutes).isBefore(LocalDateTime.now());
    }
}
