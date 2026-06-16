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
