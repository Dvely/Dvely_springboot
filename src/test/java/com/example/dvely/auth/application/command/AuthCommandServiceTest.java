package com.example.dvely.auth.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private GithubTokenCleaner githubTokenCleaner;

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
                githubTokenCleaner
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

    // ── 한 요청에서 갱신이 두 번 일어나는 경우 ────────────────────────────────────────

    @Test
    void aSecondRefreshInTheSameRequestReusesTheTokenTheFirstOneCommitted() {
        // 프로젝트 개요 조회 하나가 GitHub 을 두 번 호출한다(최근 커밋 · 저장소 상태). 두 번째
        // 호출은 바깥 영속성 컨텍스트의 옛 UserEntity 를 읽어 여전히 만료로 판정하는데, 그대로
        // 갱신하면 이미 회전된 리프레시 토큰을 들고 가 bad_refresh_token 을 맞는다.
        when(githubTokenCleaner.readValidAccessToken(10L)).thenReturn(Optional.of("fresh-access"));

        assertThat(service.refreshGithubUserToken(10L)).isEqualTo("fresh-access");

        // GitHub 을 다시 부르지 않는다 — 부르는 순간 리프레시 토큰이 또 회전한다.
        verify(githubAppPort, never()).refreshUserToken(any());
        // 그리고 무엇보다 사용자의 GitHub 연동을 지우지 않는다.
        verify(githubTokenCleaner, never()).clearAndCommit(any());
        verify(githubTokenCleaner, never()).saveAndCommit(any(), any(), any(), any());
    }

    @Test
    void refreshStillHappensWhenNoOneElseHasRefreshedYet() {
        when(githubTokenCleaner.readValidAccessToken(10L)).thenReturn(Optional.empty());
        User user = user(10L, 55L);
        user.updateUserToken("old-access", "old-refresh", LocalDateTime.now().minusMinutes(1));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(githubAppPort.refreshUserToken("old-refresh"))
                .thenReturn(new GithubAppPort.GithubUserTokenInfo("new-access", "new-refresh", 28800L, 15811200L));

        assertThat(service.refreshGithubUserToken(10L)).isEqualTo("new-access");

        verify(githubTokenCleaner).saveAndCommit(eq(10L), eq("new-access"), eq("new-refresh"), any());
        verify(githubTokenCleaner, never()).clearAndCommit(any());
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
