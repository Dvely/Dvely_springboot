package com.example.dvely.auth.infrastructure.scheduler;

import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRefreshTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    private final SpringDataRevokedTokenRepository revokedTokenRepository;
    private final SpringDataRefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();

        int deletedRevoked = revokedTokenRepository.deleteExpired(now);
        int deletedRefresh = refreshTokenRepository.deleteExpired(now);

        log.info("토큰 정리 완료 - 만료된 블랙리스트: {}건, 만료된 리프레시: {}건", deletedRevoked, deletedRefresh);
    }
}
