package com.example.dvely.deployment.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class GithubActionsClientTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void createdFilterConvertsWallClockToTheRealInstant() {
        // DB 의 LocalDateTime 은 호스트 타임존(KST)의 벽시계다. GitHub 은 진짜 UTC 로
        // 필터링하므로 그 벽시계를 KST 의 순간으로 해석해 넘겨야 한다.
        LocalDateTime triggeredAt = LocalDateTime.of(2026, 8, 18, 14, 37, 40);

        String filter = GithubActionsClient.createdFilter(triggeredAt, SEOUL);

        // 14:36:40 KST == 05:36:40 UTC. 라벨만 바꾸면 14:36:40Z 가 되어 9시간 미래를 본다.
        assertThat(filter).isEqualTo("2026-08-18T14:36:40+09:00");
        assertThat(java.time.OffsetDateTime.parse(filter).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-08-18T05:36:40Z"));
    }

    @Test
    void theFilterNeverExcludesTheRunItIsLookingFor() {
        // 실측(2026-08-19 운영): 이 배포의 실행은 05:38:03Z 에 있었는데, 잘못된 변환은
        // 14:36:40Z 부터를 요구해 실행을 범위 밖으로 밀어냈다 — 조회 결과가 늘 0건이었다.
        LocalDateTime triggeredAt = LocalDateTime.of(2026, 8, 18, 14, 37, 40);
        java.time.Instant actualRunCreatedAt = java.time.Instant.parse("2026-08-18T05:38:03Z");

        java.time.Instant filterFrom =
                java.time.OffsetDateTime.parse(GithubActionsClient.createdFilter(triggeredAt, SEOUL)).toInstant();

        assertThat(filterFrom).isBefore(actualRunCreatedAt);
    }

    @Test
    void aOneMinuteMarginIsKeptSoARunStartedJustBeforeTheRecordStillMatches() {
        // 이력의 triggeredAt 과 GitHub 이 실행을 만든 시각은 몇 초 어긋날 수 있다.
        LocalDateTime triggeredAt = LocalDateTime.of(2026, 8, 18, 14, 37, 40);

        String filter = GithubActionsClient.createdFilter(triggeredAt, SEOUL);

        assertThat(java.time.OffsetDateTime.parse(filter).toLocalDateTime())
                .isEqualTo(triggeredAt.minusMinutes(1));
    }

    @Test
    void aUtcHostProducesTheSameInstantForItsOwnWallClock() {
        // 호스트가 UTC 로 떠 있으면 그 DB 값도 UTC 벽시계다. 시스템 타임존을 그대로 쓰므로
        // 어느 쪽이든 순간이 어긋나지 않는다.
        LocalDateTime triggeredAt = LocalDateTime.of(2026, 8, 18, 5, 37, 40);

        String filter = GithubActionsClient.createdFilter(triggeredAt, ZoneId.of("UTC"));

        assertThat(java.time.OffsetDateTime.parse(filter).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-08-18T05:36:40Z"));
    }
}
