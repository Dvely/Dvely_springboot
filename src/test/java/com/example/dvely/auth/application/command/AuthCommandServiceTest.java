package com.example.dvely.auth.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.dvely.common.exception.NotFoundException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class AuthCommandServiceTest {

    @Mock private GithubOAuthPort githubOAuthPort;
    @Mock private GithubUserPort githubUserPort;
    @Mock private GithubAppPort githubAppPort;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TokenPort tokenPort;
    @Mock private TokenBlacklistPort tokenBlacklistPort;
    @Mock private OAuthStateManager oAuthStateManager;
    @Mock private GithubUserTokenRefresher tokenRefresher;

    private AuthCommandService service;

    @BeforeEach
    void setUp() {
        service = new AuthCommandService(
                githubOAuthPort,
                githubUserPort,
                githubAppPort,
                new AuthDomainService(),
                userRepository,
                refreshTokenRepository,
                tokenPort,
                tokenBlacklistPort,
                new JwtProperties("test-secret", 3_600_000L, 2_592_000_000L),
                oAuthStateManager,
                tokenRefresher
        );
    }

    @Test
    void loginUsesOauthPortsAndIssuesServiceTokens() {
        when(githubOAuthPort.getAccessToken("code")).thenReturn("oauth-token");
        when(githubUserPort.getUser("oauth-token"))
                .thenReturn(new GithubUserPort.GithubUserInfo("123", "octo", "avatar"));
        when(userRepository.findByGithubId(new GithubId("123"))).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(user(10L, null));
        when(tokenPort.createToken(10L)).thenReturn("access-token");
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TokenResult result = service.loginWithGithub(new GithubLoginCommand("code", "state"));

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.githubAppInstalled()).isFalse();
        verify(oAuthStateManager).verify("state");
        verify(githubOAuthPort).getAccessToken("code");
        verify(githubUserPort).getUser("oauth-token");
    }

    @Test
    void refreshRevokesOldTokenBeforeIssuingRotatedPair() {
        RefreshToken oldToken = new RefreshToken(
                7L,
                10L,
                "old-refresh",
                LocalDateTime.now().plusDays(1),
                false
        );
        when(refreshTokenRepository.findByToken("old-refresh")).thenReturn(Optional.of(oldToken));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user(10L, 99L)));
        when(tokenPort.createToken(10L)).thenReturn("new-access");
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TokenResult result = service.refresh("old-refresh");

        assertThat(oldToken.isRevoked()).isTrue();
        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isNotEqualTo("old-refresh");
        assertThat(result.githubAppInstalled()).isTrue();
        verify(refreshTokenRepository).save(oldToken);
    }

    @Test
    void unknownOwnerCannotLinkGithubAppOrCallExternalPort() {
        when(userRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkGithubApp(77L, 123L, "code"))
                .isInstanceOf(NotFoundException.class);

        verify(githubAppPort, never()).getUserToken(any());
        verify(userRepository, never()).save(any());
    }

    // ── 갱신이 겹치는 경우 ──────────────────────────────────────────────────────────

    @Test
    void aSecondRefreshReusesTheTokenTheFirstOneCommitted() {
        // 프로젝트 개요 조회 하나가 GitHub 을 두 번 호출한다(최근 커밋 · 저장소 상태). 뒤에 오는
        // 호출은 바깥 영속성 컨텍스트의 옛 UserEntity 를 읽어 여전히 만료로 판정하는데, 그대로
        // 갱신하면 이미 회전된 리프레시 토큰을 들고 가 bad_refresh_token 을 맞는다.
        when(tokenRefresher.readValidAccessToken(10L)).thenReturn(Optional.of("fresh-access"));

        assertThat(service.refreshGithubUserToken(10L)).isEqualTo("fresh-access");

        // 잠금까지 가지 않는다 — 대부분의 호출이 여기서 끝나야 DB 잠금 경합이 생기지 않는다.
        verify(tokenRefresher, never()).refreshWithLock(any());
        verify(githubAppPort, never()).refreshUserToken(any());
    }

    @Test
    void aRefreshThatNoOneElseHandledGoesThroughTheLock() {
        // 빠른 경로에서 못 찾으면 잠금 경로로 넘긴다. 동시에 들어온 두 흐름이 둘 다 여기까지
        // 올 수 있고, 잠금이 없으면 그 둘이 같은 리프레시 토큰을 들고 GitHub 에 간다(#162).
        when(tokenRefresher.readValidAccessToken(10L)).thenReturn(Optional.empty());
        when(tokenRefresher.refreshWithLock(10L)).thenReturn("new-access");

        assertThat(service.refreshGithubUserToken(10L)).isEqualTo("new-access");

        verify(tokenRefresher).refreshWithLock(10L);
    }

    // ── #337: 외부 호출이 실패하면 저장이 남지 않는다 ────────────────────────────────

    /**
     * 예전에는 installationId 를 먼저 반영하고 그 뒤에 토큰 교환을 호출했고, 교환이 실패하면
     * 롤백이 그 반영까지 되돌려 <b>아무것도 저장되지 않았다</b>. 트랜잭션을 걷어내면서 외부
     * 호출을 저장 앞으로 옮겨 같은 결과를 유지한다 — 이 테스트가 그 순서를 고정한다.
     */
    @Test
    void githubTokenExchangeFailing_savesNeitherTheTokenNorTheInstallationId() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(user(10L, null)));
        when(githubAppPort.getUserToken("code"))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "bad_verification_code",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.linkGithubApp(10L, 123L, "code"))
                .isInstanceOf(HttpClientErrorException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void githubUserLookupFailing_createsNoUserAndNoRefreshToken() {
        when(githubOAuthPort.getAccessToken("code")).thenReturn("oauth-token");
        when(githubUserPort.getUser("oauth-token"))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.loginWithGithub(new GithubLoginCommand("code", "state")))
                .isInstanceOf(HttpClientErrorException.class);

        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    private User user(Long id, Long installationId) {
        return new User(
                id,
                new GithubId("123"),
                "octo",
                "avatar",
                installationId,
                null,
                null,
                null
        );
    }
}
