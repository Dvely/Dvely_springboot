package com.example.dvely.provisioning.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로비저닝 설정.
 *
 * localTtl 은 LOCAL DB 의 수명이다. 프리뷰 세션 TTL(기본 30분)과 맞춰야 한다 — DB 가 프리뷰
 * 세션의 컨테이너 옆에 사는데 세션보다 오래 살면 의미가 없고, 짧으면 프리뷰가 도는 중에 DB 가
 * 먼저 사라진다. 회수 워커가 이 시각을 보고 EXPIRED 로 넘긴다.
 */
@ConfigurationProperties(prefix = "qeploy.provisioning")
public record ProvisioningProperties(
        Duration localTtl,
        Long expirySweepIntervalMs
) {
    public Duration localTtl() {
        return localTtl != null ? localTtl : Duration.ofMinutes(30);
    }

    public long expirySweepIntervalMsOrDefault() {
        return (expirySweepIntervalMs != null && expirySweepIntervalMs > 0)
                ? expirySweepIntervalMs : 60_000L;
    }
}
