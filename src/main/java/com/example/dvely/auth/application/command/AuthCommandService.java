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
     */
    @Transactional
    public TokenResult refresh(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 리프레시 토큰입니다"));

        if (!refreshToken.isValid()) {
            throw new UnauthorizedException("만료되었거나 이미 사용된 리프레시 토큰입니다");
        }

        Long userId = refreshToken.getUserId();
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

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
     * installation_id 저장 + code가 있으면 GitHub App User Token 발급
     *
     * <p>트랜잭션을 걷어내면서(#337) 순서를 바꿨다. 예전에는 installationId 를 먼저 반영하고
     * 그 뒤에 GitHub 토큰 교환을 호출했는데, 교환이 실패하면 롤백이 installationId 반영까지
     * 되돌려 <b>아무것도 저장되지 않는</b> 것이 이 메서드의 실제 동작이었다. 롤백이 사라진
     * 지금 같은 결과를 얻으려면 외부 호출을 저장보다 앞에 두는 수밖에 없다 — 교환이 던지면
     * 아래 저장 구간에 도달하지 않는다.</p>
     *
     * <p>유저 조회는 외부 호출보다 앞에 남겨 둔다. 없는 유저면 code 를 소모하기 전에 404 가
     * 나가던 기존 순서를 그대로 지키기 위해서다.</p>
     */
    public void linkGithubApp(Long userId, Long installationId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        GithubAppPort.GithubUserTokenInfo tokenInfo = code == null ? null : githubAppPort.getUserToken(code);

        // 여기부터가 저장 구간 — 위 외부 호출이 실패했다면 도달하지 않는다.
        // 재인증 콜백은 installation_id 없이 올 수 있음 — 저장된 값 유지
        if (installationId != null) {
            authDomainService.updateInstallationId(user, installationId);
        }

        if (tokenInfo != null) {
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
            user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);
            log.info("GitHub App User Token 발급 완료: userId={}", userId);
        }

        userRepository.save(user);
        log.info("GitHub App 연동 완료: userId={}, installationId={}", userId,
                installationId != null ? installationId : user.getGithubInstallationId());
    }

    /**
     * GitHub App 설치 설정 페이지(state 없음)에서 오는 콜백 처리
     * code로 User Token 발급 → GitHub 유저 정보로 DB 유저 식별
     *
     * <p>트랜잭션을 걸지 않는다 — GitHub 호출 두 번이 이미 저장보다 앞에 있어, 실패하면 저장에
     * 도달하지 않는 것은 그대로다(#337). 저장도 {@code save} 한 번뿐이라 원자성이 줄지 않는다.</p>
     */
    public void linkGithubAppByCode(Long installationId, String code) {
        if (code == null) {
            throw new IllegalArgumentException("code가 없어 유저를 식별할 수 없습니다");
        }

        GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.getUserToken(code);
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(tokenInfo.accessToken());

        User user = userRepository.findByGithubId(new GithubId(githubUser.id()))
                .orElseThrow(() -> new NotFoundException("등록된 유저가 아닙니다: githubId=" + githubUser.id()));

        authDomainService.updateInstallationId(user, installationId);

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
        user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);

        userRepository.save(user);
        log.info("GitHub App 연동 완료 (settings 경유): userId={}, installationId={}", user.getId(), installationId);
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
        RefreshToken refreshToken = new RefreshToken(userId, UUID.randomUUID().toString(), expiresAt);
        return refreshTokenRepository.save(refreshToken).getToken();
    }
}
