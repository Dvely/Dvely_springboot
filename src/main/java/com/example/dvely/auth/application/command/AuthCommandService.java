package com.example.dvely.auth.application.command;

import com.example.dvely.auth.application.command.dto.GithubLoginCommand;
import com.example.dvely.auth.application.command.dto.TokenResult;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.application.port.out.GithubUserPort;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
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
    private final GithubTokenCleaner githubTokenCleaner;

    /**
     * GitHub OAuth 로그인
     * OAuth Token은 유저 정보 조회 후 버림 (저장 X)
     */
    @Transactional
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
        String jti = tokenPort.getJti(accessToken);
        tokenBlacklistPort.revoke(jti, tokenPort.getExpiresAt(accessToken));
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    /**
     * GitHub App 설치 완료 콜백 처리
     * installation_id 저장 + code가 있으면 GitHub App User Token 발급
     */
    @Transactional
    public void linkGithubApp(Long userId, Long installationId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        // 재인증 콜백은 installation_id 없이 올 수 있음 — 저장된 값 유지
        if (installationId != null) {
            authDomainService.updateInstallationId(user, installationId);
        }

        if (code != null) {
            GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.getUserToken(code);
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
     */
    @Transactional
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
     */
    @Transactional
    public String refreshGithubUserToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId));

        if (user.getGithubUserRefreshToken() == null) {
            throw new ForbiddenException("GitHub App이 연동되지 않았습니다. GitHub App을 다시 설치해 주세요.");
        }

        try {
            GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.refreshUserToken(user.getGithubUserRefreshToken());
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
            githubTokenCleaner.saveAndCommit(userId, tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);
            log.info("GitHub App User Token 갱신 완료: userId={}", userId);
            return tokenInfo.accessToken();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("bad_refresh_token")) {
                // REQUIRES_NEW 트랜잭션으로 커밋 — 현재 트랜잭션이 롤백되어도 클리어는 유지됨
                githubTokenCleaner.clearAndCommit(userId);
                throw new ForbiddenException("GitHub App 연동이 만료되었습니다. GitHub App을 다시 설치해 주세요.");
            }
            throw e;
        }
    }

    private String issueRefreshToken(Long userId) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.refreshExpirationMs() / 1000);
        RefreshToken refreshToken = new RefreshToken(userId, UUID.randomUUID().toString(), expiresAt);
        return refreshTokenRepository.save(refreshToken).getToken();
    }
}
