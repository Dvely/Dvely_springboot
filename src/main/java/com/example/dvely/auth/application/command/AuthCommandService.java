package com.example.dvely.auth.application.command;

import com.example.dvely.auth.application.command.dto.GithubLoginCommand;
import com.example.dvely.auth.application.command.dto.TokenResult;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.application.port.out.GithubUserPort;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenClaims;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.domain.model.RefreshToken;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.RefreshTokenRepository;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.service.AuthDomainService;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.auth.infrastructure.oauth.GithubAppStateManager;
import com.example.dvely.auth.infrastructure.oauth.OAuthStateManager;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthCommandService {

    private final GithubOAuthPort githubOAuthPort;
    private final GithubUserPort githubUserPort;
    private final GithubAppPort githubAppPort;
    private final AuthDomainService authDomainService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenPort tokenPort;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final JwtProperties jwtProperties;
    private final OAuthStateManager oAuthStateManager;
    private final GithubUserTokenRefresher tokenRefresher;
    private final GithubAppStateManager githubAppStateManager;

    /**
     * GitHub OAuth 로그인
     * OAuth Token은 유저 정보 조회 후 버림 (저장 X)
     *
     * <p>트랜잭션을 걸지 않는다 — GitHub OAuth·User API 두 번을 기다리는 동안 커넥션을 붙들던
     * 자리다(#337). 로그인은 가장 자주 열리는 경로라 그 점유가 그대로 풀 고갈로 이어졌다.</p>
     *
     * <p>실패 시 동작은 그대로다: 외부 호출 두 건이 모두 저장보다 앞에 있어, 둘 중 하나라도
     * 던지면 아래 저장에 도달하지 않는다 — 예전 롤백과 같은 결과다. 저장 두 건(유저·리프레시
     * 토큰)이 더는 한 트랜잭션이 아니지만, 이 경로는 githubId 로 찾아 없으면 만드는 멱등 연산이라
     * 뒤의 저장이 실패해도 재시도가 그대로 복구한다(유저 행만 남고 손상은 없다).</p>
     */
    public TokenResult loginWithGithub(GithubLoginCommand command) {
        oAuthStateManager.verify(command.state());
        String oauthToken = githubOAuthPort.getAccessToken(command.code());
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(oauthToken);

        GithubId githubId = new GithubId(githubUser.id());
        User user = userRepository.findByGithubId(githubId)
                .map(existing -> {
                    authDomainService.updateProfile(existing, githubUser.login(), githubUser.avatarUrl());
                    return existing;
                })
                .orElseGet(() -> authDomainService.createUser(githubId, githubUser.login(), githubUser.avatarUrl()));

        User savedUser = userRepository.save(user);

        String accessToken = tokenPort.createToken(savedUser.getId());
        String refreshToken = issueRefreshToken(savedUser.getId());

        return new TokenResult(accessToken, refreshToken, savedUser.hasGithubAppInstalled());
    }

    /**
     * Refresh Token으로 서비스 Access Token 재발급 (Token Rotation)
     *
     * <p>폐기는 조건부 UPDATE 하나로 한다(#506). 같은 토큰으로 요청이 겹쳐도 그 UPDATE 가 1행을
     * 바꾼 요청만 새 토큰을 받는다. 재사용 탐지(폐기된 토큰이 다시 오면 전체 폐기)는 FE 의 refresh
     * 단일화 이후 별도로 붙인다 — 지금 넣으면 FE 가 동시에 두 번 보낼 때 정상 사용자가 로그아웃된다.</p>
     */
    @Transactional
    public TokenResult refresh(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 리프레시 토큰입니다"));

        if (!refreshTokenRepository.revokeIfActive(rawToken, LocalDateTime.now())) {
            throw new UnauthorizedException("만료되었거나 이미 사용된 리프레시 토큰입니다");
        }
        Long userId = refreshToken.getUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        String newAccessToken = tokenPort.createToken(userId);
        String newRefreshToken = issueRefreshToken(userId);

        return new TokenResult(newAccessToken, newRefreshToken, user.hasGithubAppInstalled());
    }

    /**
     * 로그아웃
     * - Access Token 블랙리스트 등록
     * - 모든 서비스 Refresh Token 폐기
     */
    @Transactional
    public void logout(Long userId, String accessToken) {
        // jti 와 만료시각을 따로 받으면 같은 토큰을 두 번 파싱한다 — 한 번에 받는다.
        TokenClaims claims = tokenPort.parseClaims(accessToken);
        tokenBlacklistPort.revoke(claims.jti(), claims.expiresAt());
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    /**
     * GitHub App 설치 완료 콜백 처리
     * installation_id 저장 + GitHub App User Token 발급
     *
     * <p>저장 전에 두 가지를 확인한다(#504). code 로 받은 User Token 의 GitHub 사용자가 state 가
     * 가리키는 유저 본인인지, 그리고 그 사용자가 installation_id 의 설치에 접근할 수 있는지다.
     * 둘 다 없으면 콜백 URL 의 값만으로 다른 사람의 설치나 GitHub 토큰이 이 유저에게 붙는다.
     * 확인에 쓸 User Token 이 없으므로 code 없는 콜백은 저장하지 않는다.</p>
     *
     * <p>트랜잭션을 걷어내면서(#337) 외부 호출을 저장보다 앞에 뒀다 — 교환이나 확인이 던지면
     * 아래 저장 구간에 도달하지 않는다. 유저 조회는 외부 호출보다 앞에 남겨, 없는 유저면 code 를
     * 소모하기 전에 404 가 나간다.</p>
     */
    public void linkGithubApp(String state, Long installationId, String code) {
        Long userId = githubAppStateManager.consume(state);
        requireCode(code);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.getUserToken(code);
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(tokenInfo.accessToken());
        if (!user.getGithubId().equals(new GithubId(githubUser.id()))) {
            throw new ForbiddenException("로그인한 계정과 다른 GitHub 계정으로 연동할 수 없습니다. "
                    + "GitHub에서 로그아웃한 뒤 Qeploy에 로그인한 계정으로 다시 시도해 주세요.");
        }
        // 재인증 콜백은 installation_id 없이 온다 — 저장된 값을 그대로 둔다
        if (installationId != null) {
            requireAccessibleInstallation(tokenInfo, installationId);
        }

        // 여기부터가 저장 구간 — 위 외부 호출·확인이 실패했다면 도달하지 않는다.
        if (installationId != null) {
            authDomainService.updateInstallationId(user, installationId);
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
        user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);

        userRepository.save(user);
        log.info("GitHub App 연동 완료: userId={}, installationId={}", userId,
                installationId != null ? installationId : user.getGithubInstallationId());
    }

    /**
     * GitHub App 설치 설정 페이지(state 없음)에서 오는 콜백 처리
     * code로 User Token 발급 → GitHub 유저 정보로 DB 유저 식별 → 설치 접근 확인(#504)
     *
     * <p>트랜잭션을 걸지 않는다 — GitHub 호출이 모두 저장보다 앞에 있어, 실패하면 저장에
     * 도달하지 않는 것은 그대로다(#337). 저장도 {@code save} 한 번뿐이라 원자성이 줄지 않는다.</p>
     */
    public void linkGithubAppByCode(Long installationId, String code) {
        requireCode(code);

        GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.getUserToken(code);
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(tokenInfo.accessToken());

        User user = userRepository.findByGithubId(new GithubId(githubUser.id()))
                .orElseThrow(() -> new NotFoundException("등록된 유저가 아닙니다: githubId=" + githubUser.id()));
        requireAccessibleInstallation(tokenInfo, installationId);

        authDomainService.updateInstallationId(user, installationId);

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
        user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);

        userRepository.save(user);
        log.info("GitHub App 연동 완료 (settings 경유): userId={}, installationId={}", user.getId(), installationId);
    }

    private void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("GitHub 인증 코드가 없어 연동할 수 없습니다. 다시 시도해 주세요.");
        }
    }

    private void requireAccessibleInstallation(GithubAppPort.GithubUserTokenInfo tokenInfo, long installationId) {
        if (!githubAppPort.canAccessInstallation(tokenInfo.accessToken(), installationId)) {
            throw new ForbiddenException("이 GitHub 계정이 접근할 수 없는 App 설치입니다. 다시 설치해 주세요.");
        }
    }

    /**
     * GitHub App User Token 갱신.
     *
     * 갱신된 액세스 토큰을 돌려주는 것이 중요하다. 저장은 REQUIRES_NEW 로 커밋되지만, 호출자가
     * 있는 바깥 트랜잭션의 영속성 컨텍스트에는 옛 UserEntity 가 남아 있어 곧바로 findById 해도
     * 갱신 전 값이 돌아온다. 그 값을 다시 쓰면 두 가지가 깨진다 — 이미 만료된 액세스 토큰으로
     * GitHub 을 호출하거나, 이미 회전돼 무효가 된 리프레시 토큰으로 또 갱신을 시도해
     * bad_refresh_token 을 맞는다(2026-08-18 운영 실측: 저장소 연결 승인이 이 경로로 실패했다).
     *
     * 그러니 갱신 후에는 다시 읽지 말고 이 반환값을 쓸 것.
     *
     * <p>여기에는 트랜잭션을 걸지 않는다(#337). 이 메서드는 자기 DB 작업이 없고 아래 두 호출이
     * 모두 {@code REQUIRES_NEW} 라, 바깥 트랜잭션은 GitHub 갱신을 기다리는 내내 아무 일도 하지
     * 않으면서 커넥션 하나를 더 붙들고 있을 뿐이었다(안쪽까지 합쳐 동시에 두 개). 되돌릴 것이
     * 없으니 롤백에 기대던 동작도 없다.</p>
     */
    public String refreshGithubUserToken(Long userId) {
        // 빠른 경로 — 다른 흐름이 이미 갱신했으면 잠금까지 가지 않는다. 그 갱신은 별도
        // 트랜잭션으로 커밋되지만 호출자의 영속성 컨텍스트에는 옛 UserEntity 가 남아 여전히
        // 만료로 보이므로, 커밋된 최신 상태를 따로 읽어 확인한다.
        //
        // 이 확인이 없으면 두 번째 호출이 이미 회전돼 무효가 된 리프레시 토큰을 들고 GitHub 에
        // 가서 bad_refresh_token 을 맞고, 그 catch 가 사용자의 GitHub 연동을 통째로 지운다.
        // 실제로 그렇게 날아갔다(2026-08-18 운영). 프로젝트 개요 조회 하나가 GitHub 을 두 번
        // 호출하는데(최근 커밋 · 저장소 상태) 둘 다 이 경로를 탔다.
        Optional<String> alreadyRefreshed = tokenRefresher.readValidAccessToken(userId);
        if (alreadyRefreshed.isPresent()) {
            log.info("GitHub App User Token 갱신 생략 — 이미 갱신됨: userId={}", userId);
            return alreadyRefreshed.get();
        }

        // 느린 경로 — 유저 행을 잠그고 갱신한다. 위 확인과 실제 갱신 사이에는 틈이 있어서,
        // 동시에 들어온 두 흐름이 둘 다 여기까지 올 수 있다. 잠금이 없으면 그 둘이 같은
        // 리프레시 토큰을 들고 GitHub 에 가고, 뒤에 도착한 쪽이 연동을 지운다(#162).
        return tokenRefresher.refreshWithLock(userId);
    }

    private String issueRefreshToken(Long userId) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.refreshExpirationMs() / 1000);
        String rawToken = UUID.randomUUID().toString();
        // 저장되는 것은 해시뿐이다 — 원문은 여기서 응답으로 한 번 나가고 끝난다.
        refreshTokenRepository.saveNew(new RefreshToken(userId, rawToken, expiresAt));
        return rawToken;
    }
}
