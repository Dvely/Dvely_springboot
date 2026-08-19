package com.example.dvely.deployment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멈춘 배포 회수 워커 설정.
 *
 * grace 는 "웹훅을 놓쳤다고 볼 시각"이다. 정상 배포는 1분 안팎이면 끝나고 웹훅도 그때 온다.
 * 너무 짧게 잡으면 아직 도는 중인 배포마다 GitHub API 를 헛되이 때린다.
 *
 * abandon 은 "결과를 못 얻은 채로 닫을 시각"이다. 넉넉해야 한다 — 여기서 닫힌 배포는 실제로는
 * 성공했을 수도 있다.
 */
@ConfigurationProperties(prefix = "qeploy.deployment.recovery")
public record StuckDeploymentRecoveryProperties(
        Long pollIntervalMs,
        Integer batchSize,
        Integer graceMinutes,
        Integer abandonMinutes
) {

    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int DEFAULT_GRACE_MINUTES = 10;
    private static final int DEFAULT_ABANDON_MINUTES = 120;

    public int batchSizeOrDefault() {
        if (batchSize == null || batchSize < 1) {
            return DEFAULT_BATCH_SIZE;
        }
        return batchSize;
    }

    public int graceMinutesOrDefault() {
        if (graceMinutes == null || graceMinutes < 1) {
            return DEFAULT_GRACE_MINUTES;
        }
        return graceMinutes;
    }

    public int abandonMinutesOrDefault() {
        if (abandonMinutes == null || abandonMinutes < 1) {
            return DEFAULT_ABANDON_MINUTES;
        }
        return abandonMinutes;
    }
}
