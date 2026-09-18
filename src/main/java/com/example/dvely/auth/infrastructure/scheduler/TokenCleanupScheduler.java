package com.example.dvely.auth.infrastructure.scheduler;

import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRefreshTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRevokedTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
public class TokenCleanupScheduler {

    private final SpringDataRevokedTokenRepository revokedTokenRepository;
    private final SpringDataRefreshTokenRepository refreshTokenRepository;
    private final ApiTokenRepository apiTokenRepository;
    private final long apiTokenGraceDays;

    public TokenCleanupScheduler(SpringDataRevokedTokenRepository revokedTokenRepository,
                                 SpringDataRefreshTokenRepository refreshTokenRepository,
                                 ApiTokenRepository apiTokenRepository,
                                 @Value("${qeploy.api-token.expired-grace-days:30}") long apiTokenGraceDays) {
        this.revokedTokenRepository = revokedTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.apiTokenGraceDays = apiTokenGraceDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();

        int deletedRevoked = revokedTokenRepository.deleteExpired(now);
        int deletedRefresh = refreshTokenRepository.deleteExpired(now);

        // 폐기됐지만 아직 만료 전인 리프레시 토큰(#338). 이 행들은 다시 유효해질 수 없는데도
        // 만료일까지 남아 있었다 — deleteExpired 의 조건이 expires_at 뿐이었기 때문이다.
        int deletedRevokedRefresh = refreshTokenRepository.deleteRevoked();

        // 만료된 PAT(#338). 만료 직후가 아니라 유예 기간 뒤에 지운다 — 목록에서 "만료됨"을
        // 잠시 보여줘야 사용자가 인증이 왜 깨졌는지 알 수 있다.
        int deletedApiTokens = apiTokenRepository.deleteExpiredBefore(now.minusDays(apiTokenGraceDays));

        log.info("토큰 정리 완료 - 블랙리스트: {}건, 리프레시(만료): {}건, 리프레시(폐기): {}건, API 토큰: {}건",
                deletedRevoked, deletedRefresh, deletedRevokedRefresh, deletedApiTokens);
    }
}
