package com.example.dvely.domainbinding.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 도메인 검증 워커 설정.
 *
 * 포기 시각(TTL)을 도메인 타입별로 나눈 이유가 있다. 관리형 서브도메인은 우리가 Cloudflare
 * 레코드를 직접 만들므로 몇 분 안에 붙어야 정상이고, 그보다 오래 걸리면 붙을 일이 없다.
 * 커스텀 도메인은 사용자가 자기 registrar 에서 CNAME 을 거는 것을 기다려야 해서 하루를 준다.
 * 같은 값을 쓰면 둘 중 하나는 반드시 틀린다 — 관리형을 너무 오래 붙들거나, 커스텀을 사용자가
 * 손도 대기 전에 FAILED 로 닫아버린다.
 */
@ConfigurationProperties(prefix = "qeploy.domain-binding.verification")
public record DomainVerificationProperties(
        Long pollIntervalMs,
        Integer batchSize,
        Integer managedTtlMinutes,
        Integer customTtlMinutes
) {

    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int DEFAULT_MANAGED_TTL_MINUTES = 30;
    private static final int DEFAULT_CUSTOM_TTL_MINUTES = 1440;

    public int batchSizeOrDefault() {
        if (batchSize == null || batchSize < 1) {
            return DEFAULT_BATCH_SIZE;
        }
        return batchSize;
    }

    public int managedTtlMinutesOrDefault() {
        if (managedTtlMinutes == null || managedTtlMinutes < 1) {
            return DEFAULT_MANAGED_TTL_MINUTES;
        }
        return managedTtlMinutes;
    }

    public int customTtlMinutesOrDefault() {
        if (customTtlMinutes == null || customTtlMinutes < 1) {
            return DEFAULT_CUSTOM_TTL_MINUTES;
        }
        return customTtlMinutes;
    }
}
