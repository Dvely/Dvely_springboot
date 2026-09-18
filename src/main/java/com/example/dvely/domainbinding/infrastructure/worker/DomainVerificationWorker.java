package com.example.dvely.domainbinding.infrastructure.worker;

import com.example.dvely.common.worker.NextCheckSchedule;
import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.infrastructure.config.DomainVerificationProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class DomainVerificationWorker {

    // CONNECTED 후 HTTPS 뱃지(httpsEnforced)를 채우려 재검증하는 창. EC2(Caddy on-demand TLS)는 첫 https
    // 요청 때 인증서를 발급하므로, 바인딩 직후 프로브가 미발급으로 false 였던 것이 재검증(프로브가 그 요청을
    // 보내 warming)으로 곧 true 가 된다. 무한 프로브를 막으려 생성 후 이 창 안에서만 재검증한다(#6).
    private static final int HTTPS_RECHECK_WINDOW_MINUTES = 30;

    // #340 5-5: 생성 후 경과 시간별 재검증 간격. 갓 만든 도메인은 곧 붙을 수 있으니 촘촘히 보고,
    // 한 시간이 지나도 안 붙었으면 사용자가 registrar 설정을 아직 안 했을 가능성이 높다 —
    // 그때부터는 성기게 본다.
    private static final Duration YOUNG_DOMAIN_WINDOW = Duration.ofMinutes(10);
    private static final Duration YOUNG_DOMAIN_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MIDDLE_AGED_DOMAIN_WINDOW = Duration.ofHours(1);
    private static final Duration MIDDLE_AGED_DOMAIN_INTERVAL = Duration.ofMinutes(5);
    private static final Duration OLD_DOMAIN_INTERVAL = Duration.ofMinutes(10);

    private final DomainBindingRepository domainBindingRepository;
    private final DomainBindingCommandService domainBindingCommandService;
    private final DomainVerificationProperties properties;
    private final Executor verificationExecutor;

    /**
     * 도메인별 재검증 간격(#340 5-5). 예전에는 배치 전체를 <b>매 주기(60초)</b> 검증했다 —
     * 커스텀 도메인은 TTL 이 1440분이므로 한 건당 최대 1,440회, 그것도 Cloudflare + GitHub +
     * HTTPS 프로브 세 묶음이다.
     *
     * <p>기다리는 대상이 DNS 전파와 호스팅 반영이라 시간이 지날수록 "다음 1분 안에 바뀔"
     * 확률이 떨어진다. 그래서 갓 만든 도메인은 촘촘히, 오래된 도메인은 성기게 본다.</p>
     */
    private final NextCheckSchedule<Long> verifySchedule = new NextCheckSchedule<>();

    /** CONNECTED 후 HTTPS 뱃지 재검증도 같은 이유로 간격을 둔다 — 위와 키가 겹치면 안 되므로 따로 둔다. */
    private final NextCheckSchedule<Long> httpsRecheckSchedule = new NextCheckSchedule<>();

    public DomainVerificationWorker(DomainBindingRepository domainBindingRepository,
                                    DomainBindingCommandService domainBindingCommandService,
                                    DomainVerificationProperties properties,
                                    @Qualifier("domainVerificationExecutor") Executor verificationExecutor) {
        this.domainBindingRepository = domainBindingRepository;
        this.domainBindingCommandService = domainBindingCommandService;
        this.properties = properties;
        this.verificationExecutor = verificationExecutor;
    }

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
                verifySchedule.clear(domain.getId());
                continue;
            }
            if (!verifySchedule.due(domain.getId())) {
                continue;   // #340 5-5: 아직 다시 볼 때가 아니다 — 외부 API 호출을 아끼는 지점
            }
            verifySchedule.scheduleAfter(domain.getId(), recheckIntervalFor(domain).toMillis());
            verify(domain);
        }
        recheckHttpsForConnectedDomains();
    }

    /**
     * CONNECTED 지만 httpsEnforced 가 아직 false 인 도메인을 재검증해 HTTPS 뱃지를 채운다(#6). 바인딩 직후엔
     * Caddy 인증서가 아직 없어 프로브가 false 였는데, 워커가 CONNECTED 이후엔 재검증을 안 해 사용자가 수동
     * "검증 재시도" 를 눌러야만 뱃지가 떴다. 재검증 프로브가 https 요청을 보내 인증서를 warming 하므로 다음
     * 주기엔 true 로 바뀌고 이 목록에서 빠진다. warming 창 안에서만 돌아 무한 프로브를 막는다.
     */
    private void recheckHttpsForConnectedDomains() {
        for (DomainBinding domain : domainBindingRepository.findConnectedPendingHttps(
                properties.batchSizeOrDefault())) {
            if (domain.getType() == DomainType.PURCHASABLE_DOMAIN) {
                continue;
            }
            if (!withinHttpsRecheckWindow(domain)) {
                continue;   // 창을 지났는데도 https 미확인 — 수동 재검증에 맡긴다(무한 프로브 방지).
            }
            if (!httpsRecheckSchedule.due(domain.getId())) {
                continue;
            }
            httpsRecheckSchedule.scheduleAfter(domain.getId(), recheckIntervalFor(domain).toMillis());
            submit(() -> {
                try {
                    // verify() 와 달리 "검증 완료" 로그를 남기지 않는다 — 이미 CONNECTED 라 매 주기 스팸이 된다.
                    domainBindingCommandService.checkVerificationAsSystem(domain.getId());
                } catch (RuntimeException exception) {
                    log.debug("HTTPS 재검증 실패(다음 주기 재시도): domainId={} 원인={}",
                            domain.getId(), exception.toString());
                }
            }, domain);
        }
    }

    private boolean withinHttpsRecheckWindow(DomainBinding domain) {
        LocalDateTime createdAt = domain.getCreatedAt();
        return createdAt != null
                && createdAt.plusMinutes(HTTPS_RECHECK_WINDOW_MINUTES).isAfter(LocalDateTime.now());
    }

    /**
     * #340 5-5: 검증 자체를 전용 executor 로 넘긴다. Cloudflare + GitHub + HTTPS 프로브 세 묶음이
     * 배치 20건만큼 <b>직렬로</b> 스케줄러 스레드를 붙들고 있었고, 한 건이 타임아웃까지 버티면 그
     * 시간이 그대로 스케줄러 점유가 됐다 — 같은 풀을 쓰는 다른 잡까지 함께 밀린다.
     */
    private void verify(DomainBinding domain) {
        submit(() -> {
            try {
                // 한 도메인의 실패가 나머지를 막지 않아야 한다. Cloudflare·GitHub 호출이 섞여 있어
                // 어느 하나는 언제든 실패할 수 있고, 다음 주기에 다시 시도하면 되는 성격이다.
                DomainStatus status = domainBindingCommandService
                        .checkVerificationAsSystem(domain.getId())
                        .status();
                if (status == DomainStatus.CONNECTED) {
                    log.info("도메인 검증 완료: domainId={} hostname={}", domain.getId(), domain.getHostname());
                    verifySchedule.clear(domain.getId());
                }
            } catch (RuntimeException exception) {
                log.warn("도메인 검증 실패 — 다음 주기에 재시도: domainId={} hostname={} 원인={}",
                        domain.getId(), domain.getHostname(), exception.toString());
            }
        }, domain);
    }

    /**
     * executor 제출 자체가 실패해도(포화) 이 도메인의 이번 차례만 건너뛴다. 검증에는 claim 이
     * 없어 되돌릴 상태가 없다 — 다음 차례에 다시 집으면 그만이다. 다만 간격은 이미 잡혔으므로
     * "바로 다음 주기"가 아니라 그 간격 뒤에 다시 온다는 점을 알고 넘긴다: 포화는 우리가 이미
     * 많이 돌고 있다는 뜻이라, 그 상황에서 서둘러 재시도하는 것이 옳지 않다.
     */
    private void submit(Runnable task, DomainBinding domain) {
        try {
            verificationExecutor.execute(task);
        } catch (RuntimeException exception) {
            log.warn("도메인 검증 위임 실패 — 다음 차례에 재시도: domainId={} 원인={}",
                    domain.getId(), exception.toString());
        }
    }

    /** 생성 후 경과 시간이 길수록 성기게 본다. createdAt 이 없으면 가장 촘촘한 간격을 쓴다. */
    private Duration recheckIntervalFor(DomainBinding domain) {
        LocalDateTime createdAt = domain.getCreatedAt();
        if (createdAt == null) {
            return YOUNG_DOMAIN_INTERVAL;
        }
        Duration age = Duration.between(createdAt, LocalDateTime.now());
        if (age.compareTo(YOUNG_DOMAIN_WINDOW) <= 0) {
            return YOUNG_DOMAIN_INTERVAL;
        }
        if (age.compareTo(MIDDLE_AGED_DOMAIN_WINDOW) <= 0) {
            return MIDDLE_AGED_DOMAIN_INTERVAL;
        }
        return OLD_DOMAIN_INTERVAL;
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
