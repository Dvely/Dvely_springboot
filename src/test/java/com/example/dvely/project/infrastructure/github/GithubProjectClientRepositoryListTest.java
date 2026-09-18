package com.example.dvely.project.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.project.application.port.out.GithubRepositoryPort.GithubRepository;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 저장소 목록 캐시가 실제 호출 경로에서 어떻게 동작하는지 확인한다.
 *
 * <p>캐시 자체의 단위 성질은 {@code RepositoryListCacheTest} 가 본다. 여기서 확인하는 것은
 * <b>GitHub 왕복이 실제로 줄었는가</b>와 <b>그 절약이 사용자 경계를 넘지 않는가</b>다. 뒤엣것이
 * 어긋나면 남의 비공개 저장소 목록이 보인다.</p>
 */
class GithubProjectClientRepositoryListTest {

    private static final String REPOSITORIES_URL =
            "https://api.github.com/installation/repositories?per_page=100&page=1";

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;
    private static final Long ALICE_INSTALLATION = 100L;
    private static final Long BOB_INSTALLATION = 200L;

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

        when(userRepository.findById(ALICE)).thenReturn(Optional.of(user(ALICE, ALICE_INSTALLATION)));
        when(userRepository.findById(BOB)).thenReturn(Optional.of(user(BOB, BOB_INSTALLATION)));
        when(githubAppPort.getInstallationToken(ALICE_INSTALLATION)).thenReturn("token-of-alice");
        when(githubAppPort.getInstallationToken(BOB_INSTALLATION)).thenReturn("token-of-bob");
    }

    @Test
    void 같은_사용자의_연이은_조회는_GitHub_을_다시_읽지_않는다() {
        // 목록 한 번이 왕복 최대 10 번이다. 저장소 선택 화면은 뒤로 갔다 오는 것만으로 다시 부른다.
        expectListing(ExpectedCount.once(), "token-of-alice", "alice/app");

        List<GithubRepository> first = client.listRepositories(ALICE);
        List<GithubRepository> second = client.listRepositories(ALICE);

        // 두 번째 호출이 나갔다면 예상하지 않은 요청으로 여기서 실패한다.
        server.verify();
        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(GithubRepository::fullName).containsExactly("alice/app");
    }

    @Test
    void 사용자마다_자기_목록만_받는다() {
        expectListing(ExpectedCount.once(), "token-of-alice", "alice/secret-app");
        expectListing(ExpectedCount.once(), "token-of-bob", "bob/app");

        List<GithubRepository> alice = client.listRepositories(ALICE);
        List<GithubRepository> bob = client.listRepositories(BOB);

        server.verify();
        assertThat(alice).extracting(GithubRepository::fullName).containsExactly("alice/secret-app");
        assertThat(bob).extracting(GithubRepository::fullName).containsExactly("bob/app");
    }

    @Test
    void 캐시가_찬_뒤에_들어온_다른_사용자도_자기_토큰으로_새로_읽는다() {
        // 캐시가 사용자 단위가 아니면 여기서 Bob 이 Alice 의 목록을 그대로 받는다.
        expectListing(ExpectedCount.once(), "token-of-alice", "alice/secret-app");
        expectListing(ExpectedCount.once(), "token-of-bob", "bob/app");

        client.listRepositories(ALICE);
        client.listRepositories(ALICE);
        List<GithubRepository> bob = client.listRepositories(BOB);

        server.verify();
        assertThat(bob).extracting(GithubRepository::fullName).doesNotContain("alice/secret-app");
    }

    @Test
    void refresh_는_캐시를_버리고_다시_읽는다() {
        // GitHub 에서 방금 저장소를 만들고 넘어온 사용자를 60 초 기다리게 할 수 없다.
        expectListing(ExpectedCount.once(), "token-of-alice", "alice/app");
        expectListing(ExpectedCount.once(), "token-of-alice", "alice/just-created");

        client.listRepositories(ALICE, false);
        List<GithubRepository> refreshed = client.listRepositories(ALICE, true);

        server.verify();
        assertThat(refreshed).extracting(GithubRepository::fullName).containsExactly("alice/just-created");
    }

    private void expectListing(ExpectedCount count, String expectedToken, String repositoryFullName) {
        server.expect(count, requestTo(REPOSITORIES_URL))
                .andExpect(header("Authorization", Matchers.equalTo("Bearer " + expectedToken)))
                .andRespond(withSuccess(listingJson(repositoryFullName), MediaType.APPLICATION_JSON));
    }

    private static String listingJson(String fullName) {
        String owner = fullName.split("/")[0];
        String name = fullName.split("/")[1];
        return """
                {"total_count":1,"repositories":[{
                  "full_name":"%s","name":"%s","owner":{"login":"%s"},
                  "description":null,"private":true,"default_branch":"main",
                  "updated_at":"2026-09-01T00:00:00Z"
                }]}
                """.formatted(fullName, name, owner);
    }

    private static User user(Long id, Long installationId) {
        return new User(id, new GithubId(String.valueOf(id)), "user" + id, null,
                installationId, null, null, null);
    }

    private static GithubProperties properties() {
        return new GithubProperties(
                new GithubProperties.OAuthProperties("oauth-id", "oauth-secret", "https://qeploy.com/cb", "read:user"),
                new GithubProperties.AppProperties("1", "/tmp/none.pem", "https://qeploy.com/app/cb",
                        "webhook-secret", "app-id", "app-secret"));
    }
}
