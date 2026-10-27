package com.example.dvely.project.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.common.exception.GithubAppReauthorizationRequiredException;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 사용자 토큰을 쓸 수 없을 때 재인증 코드로 나가는지, 설치 토큰으로 될 일은 대체하는지(#516). */
class GithubProjectClientReauthorizationTest {

    private static final Long OWNER = 1L;
    private static final Long INSTALLATION = 100L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final GithubAppPort githubAppPort = mock(GithubAppPort.class);
    private final AuthCommandService authCommandService = mock(AuthCommandService.class);

    private MockRestServiceServer server;
    private GithubProjectClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubProjectClient(
                userRepository, githubAppPort, authCommandService, properties(), builder.build());
    }

    @Test
    void creatingARepositoryWithoutAUserTokenAsksToReauthorize() {
        // 예전에는 IllegalStateException(409 CONFLICT) 이라 화면이 진입점을 못 띄웠다.
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(user(null, null, null)));

        assertThatThrownBy(() -> client.createRepository(OWNER, "app", RepositoryVisibility.PRIVATE))
                .isInstanceOf(GithubAppReauthorizationRequiredException.class);
    }

    @Test
    void aRejectedRefreshReachesTheCallerWithItsCodeIntact() {
        // 갱신 실패를 IllegalStateException 으로 감싸면 코드가 사라진다.
        when(userRepository.findById(OWNER))
                .thenReturn(Optional.of(user("old-access", "refresh", LocalDateTime.now().minusMinutes(1))));
        when(authCommandService.refreshGithubUserToken(OWNER))
                .thenThrow(new GithubAppReauthorizationRequiredException());

        assertThatThrownBy(() -> client.createRepository(OWNER, "app", RepositoryVisibility.PRIVATE))
                .isInstanceOf(GithubAppReauthorizationRequiredException.class);
    }

    @Test
    void repositoryReadsFallBackToTheInstallationTokenInsteadOfAskingToReauthorize() {
        // 저장소 상태 확인은 설치 토큰으로도 된다 — 사용자 토큰이 죽었다고 막을 이유가 없다.
        when(userRepository.findById(OWNER))
                .thenReturn(Optional.of(user("old-access", "refresh", LocalDateTime.now().minusMinutes(1))));
        when(authCommandService.refreshGithubUserToken(OWNER))
                .thenThrow(new GithubAppReauthorizationRequiredException());
        when(githubAppPort.getInstallationToken(INSTALLATION)).thenReturn("installation-token");
        server.expect(requestTo("https://api.github.com/repos/octo/app"))
                .andExpect(header("Authorization", "Bearer installation-token"))
                .andRespond(withSuccess());

        assertThat(client.checkRepositoryHealth(OWNER, "octo/app")).isEqualTo(RepositoryHealthStatus.HEALTHY);
        server.verify();
    }

    private static User user(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        return new User(OWNER, new GithubId("1"), "octo", null, INSTALLATION, accessToken, refreshToken, expiresAt);
    }

    private static GithubProperties properties() {
        return new GithubProperties(
                new GithubProperties.OAuthProperties("oauth-id", "oauth-secret", "https://qeploy.com/cb", "read:user"),
                new GithubProperties.AppProperties("1", "/tmp/none.pem", "https://qeploy.com/app/cb",
                        "webhook-secret", "app-id", "app-secret"));
    }
}
