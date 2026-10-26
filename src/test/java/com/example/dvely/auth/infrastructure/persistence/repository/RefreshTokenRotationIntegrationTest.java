package com.example.dvely.auth.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.RefreshToken;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.RefreshTokenRepository;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.common.exception.UnauthorizedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 리프레시 토큰 해시 저장과 원자적 회전을 실제 MySQL 로 확인한다(#506). */
@SpringBootTest
class RefreshTokenRotationIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired private AuthCommandService authCommandService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void onlyOneOfTwoConcurrentRefreshesWithTheSameTokenSucceeds() throws Exception {
        Long userId = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 한 번으로는 겹치지 않을 수 있어 여러 번 돌린다. 매 판 정확히 하나만 성공해야 한다.
            for (int round = 0; round < ROUNDS; round++) {
                String raw = issue(userId);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        try {
                            authCommandService.refresh(raw);
                            return true;
                        } catch (UnauthorizedException e) {
                            return false;
                        }
                    }));
                }
                start.countDown();

                int succeeded = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(30, TimeUnit.SECONDS)) {
                        succeeded++;
                    }
                }
                assertThat(succeeded).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
            cleanup(userId);
        }
    }

    @Test
    void onlyTheHashIsStoredAndItMatchesTheMigrationsSha2() {
        Long userId = newUser();
        try {
            String raw = issue(userId);

            List<String> stored = jdbcTemplate.queryForList(
                    "SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, userId);
            assertThat(stored).containsExactly(RefreshTokenHash.of(raw));
            assertThat(stored.getFirst()).isNotEqualTo(raw).hasSize(64);
            // V67 이 기존 평문 행을 SHA2(token, 256) 으로 바꿨다. 앱의 해시와 같아야 이전 토큰이 이어진다.
            String sha2 = jdbcTemplate.queryForObject("SELECT SHA2(?, 256)", String.class, raw);
            assertThat(sha2).isEqualTo(RefreshTokenHash.of(raw));
            assertThat(refreshTokenRepository.findByToken(raw)).isPresent();
        } finally {
            cleanup(userId);
        }
    }

    private Long newUser() {
        return userRepository.save(new User(new GithubId("i506-refresh-" + System.nanoTime()), "octo", null)).getId();
    }

    private String issue(Long userId) {
        String raw = UUID.randomUUID().toString();
        refreshTokenRepository.saveNew(new RefreshToken(userId, raw, LocalDateTime.now().plusDays(1)));
        return raw;
    }

    private void cleanup(Long userId) {
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
    }
}
