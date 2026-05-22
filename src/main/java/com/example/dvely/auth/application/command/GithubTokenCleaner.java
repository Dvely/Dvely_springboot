package com.example.dvely.auth.application.command;

import com.example.dvely.auth.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubTokenCleaner {

    private final UserRepository userRepository;

    // 외부 트랜잭션이 롤백되더라도 토큰 갱신은 반드시 커밋되어야 하므로 REQUIRES_NEW
    // GitHub는 이미 old refresh_token을 무효화했으므로 롤백되면 다음 갱신 시 bad_refresh_token 발생
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndCommit(Long userId, String accessToken, String refreshToken, LocalDateTime expiresAt) {
        userRepository.findById(userId).ifPresent(user -> {
            user.updateUserToken(accessToken, refreshToken, expiresAt);
            userRepository.save(user);
            log.info("GitHub App 토큰 갱신 커밋 완료: userId={}", userId);
        });
    }

    // 외부 트랜잭션이 롤백되더라도 토큰 클리어는 반드시 커밋되어야 하므로 REQUIRES_NEW
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearAndCommit(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.clearGithubAppToken();
            userRepository.save(user);
            log.warn("GitHub App 토큰 초기화 커밋 완료: userId={}", userId);
        });
    }
}
