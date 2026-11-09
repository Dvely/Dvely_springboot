package com.example.dvely.auth.infrastructure.scheduler;

import com.example.dvely.apitoken.domain.repository.ApiTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRefreshTokenRepository;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataRevokedTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
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

    /**
     * <b>READ COMMITTED 로 돈다(#625).</b> {@code deleteRevoked} 는 {@code revoked} 에 인덱스가 없어 표 전체를 훑는다.
     * 기본 격리(REPEATABLE READ)에서는 그렇게 훑은 행과 그 사이 틈을 <b>커밋까지 전부</b> 잠그므로, 정리가 도는 동안 다른
     * 사용자의 토큰 회전({@code revokeIfActive})과 로그인({@code refresh_tokens} INSERT)이 멈춘다. 실측(2만 행 중 1.5만
     * 폐기): 정리를 쥔 사이 회전이 잠금 대기 2초로 실패했고 로그인도 정리가 끝날 때까지 기다렸다. READ COMMITTED 에서는
     * 조건에 맞지 않는 행의 잠금을 바로 놓고 틈은 잠그지 않아 둘 다 즉시 끝났다(12ms·8ms).
     *
     * <p>전제는 binlog 형식 ROW(MySQL 8.0 기본)다 — STATEMENT 에서는 READ COMMITTED 로 쓰기를 할 수 없다. 같은 이유로
     * 에이전트 런 보존 스윕도 READ COMMITTED 로 돈다(#618, {@code AgentRunRetentionStore}).</p>
     *
     * <p>{@code revoked} 에 인덱스를 거는 대신 이렇게 한 이유: 토큰을 회전할 때마다 옛 행이 폐기되므로 폐기 행이 대개 다수라
     * 인덱스가 거르는 몫이 작다. 폐기할 때 {@code expires_at} 을 당겨 만료 경로({@code deleteExpired}, 인덱스 범위)로 합치는
     * 방법도 재 봤고 효과는 같았지만, {@code expires_at} 의 뜻을 바꾸고 기존 행을 고치는 데이터 마이그레이션이 필요하다.</p>
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
