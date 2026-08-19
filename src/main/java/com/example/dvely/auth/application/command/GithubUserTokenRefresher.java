package com.example.dvely.auth.application.command;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.exception.NotFoundException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GitHub App User Token 갱신을 소유한다.
 *
 * GitHub 은 리프레시 토큰을 쓸 때마다 회전시킨다. 그래서 같은 유저의 갱신이 겹치면 뒤에 도착한
 * 쪽이 이미 무효가 된 값을 들고 가 bad_refresh_token 을 맞고, 그 catch 가 연동을 통째로 지운다 —
 * 앞선 흐름이 방금 받아온 멀쩡한 토큰까지 함께 사라진다. 사용자는 아무 잘못 없이 재인증을 해야
 * 한다. 겹치는 조합은 드물지 않다. 사용자 요청뿐 아니라 도메인 검증·배포 회수 워커도 같은 경로를
 * 타고, 그것들은 1분마다 돈다.
 *
 * 그래서 갱신 구간 전체를 유저 행 잠금으로 직렬화한다. 뒤에 온 쪽은 앞이 끝날 때까지 기다렸다가
 * 잠금을 얻은 뒤 상태를 다시 보고, 이미 갱신돼 있으면 GitHub 을 부르지 않고 그 토큰을 그대로
 * 쓴다.
 *
 * 잠금은 저장과 같은 트랜잭션에 있어야 한다. 바깥에서 잠그고 저장만 REQUIRES_NEW 로 떼면, 안쪽
 * 트랜잭션이 바깥이 쥔 잠금을 기다리다 교착된다. 그래서 이 메서드 하나가 잠금·조회·GitHub 호출·
 * 저장을 모두 REQUIRES_NEW 안에서 끝낸다. 호출한 쪽이 롤백돼도 갱신은 남아야 하기 때문이기도
 * 하다 — GitHub 은 이미 옛 리프레시 토큰을 버렸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubUserTokenRefresher {

    private final UserRepository userRepository;
    private final GithubAppPort githubAppPort;

    /**
     * 커밋된 최신 액세스 토큰을 잠금 없이 읽는다. 아직 만료되지 않았을 때만 값이 있다.
     *
     * 호출자의 영속성 컨텍스트에는 옛 UserEntity 가 남아 있을 수 있어 그냥 findById 하면 이미
     * 커밋된 새 토큰이 안 보인다. 새 트랜잭션은 새 영속성 컨텍스트를 쓰므로 커밋된 값을 본다.
     * 잠금을 잡기 전의 빠른 경로다 — 대부분의 호출은 여기서 끝나고 DB 잠금까지 가지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> readValidAccessToken(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isUserAccessTokenExpired())
                .map(User::getGithubUserAccessToken)
                .filter(token -> token != null && !token.isBlank());
    }

    /**
     * 유저 행을 잠근 채 갱신한다.
     *
     * ForbiddenException 은 롤백 대상에서 뺀다. bad_refresh_token 을 맞았을 때 토큰을 지운 뒤
     * 예외를 던지는데, 롤백되면 그 지움이 사라져 다음 호출이 같은 무효 토큰으로 또 GitHub 에
     * 간다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ForbiddenException.class)
    public String refreshWithLock(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        // 잠금을 기다리는 동안 앞선 흐름이 갱신을 마쳤을 수 있다. 그 경우 GitHub 을 부르면
        // 이미 회전된 리프레시 토큰을 쓰게 되므로, 여기서 멈추는 것이 이 잠금의 핵심이다.
        String current = user.getGithubUserAccessToken();
        if (!user.isUserAccessTokenExpired() && current != null && !current.isBlank()) {
            log.info("GitHub App User Token 갱신 생략 — 잠금 획득 후 이미 갱신됨: userId={}", userId);
            return current;
        }

        if (user.getGithubUserRefreshToken() == null) {
            throw new ForbiddenException("GitHub App이 연동되지 않았습니다. GitHub App을 다시 설치해 주세요.");
        }

        try {
            GithubAppPort.GithubUserTokenInfo tokenInfo =
                    githubAppPort.refreshUserToken(user.getGithubUserRefreshToken());
            user.updateUserToken(
                    tokenInfo.accessToken(),
                    tokenInfo.refreshToken(),
                    LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds())
            );
            userRepository.save(user);
            log.info("GitHub App User Token 갱신 완료: userId={}", userId);
            return tokenInfo.accessToken();
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("bad_refresh_token")) {
                user.clearGithubAppToken();
                userRepository.save(user);
                log.warn("GitHub App 토큰 초기화 — 리프레시 토큰이 거부됨: userId={}", userId);
                throw new ForbiddenException("GitHub App 연동이 만료되었습니다. GitHub App을 다시 설치해 주세요.");
            }
            throw exception;
        }
    }
}
