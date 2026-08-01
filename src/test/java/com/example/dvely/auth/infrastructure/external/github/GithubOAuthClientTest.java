package com.example.dvely.auth.infrastructure.external.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인가 URL 생성의 인코딩 회귀 방지.
 *
 * <p>UriComponentsBuilder는 {@code build()} 뒤에 {@code encode()}를 붙이지 않으면 쿼리 값을 날것
 * 그대로 이어 붙인다. scope는 {@code "user:email read:user"} 처럼 공백이 구분자라, 인코딩이 빠지면
 * 쿼리 문자열에 raw 공백이 들어간 RFC 3986 위반 URL이 만들어진다. 브라우저는 알아서 보정해 주기
 * 때문에 수동 테스트로는 드러나지 않지만, URL 파서를 쓰는 HTTP 클라이언트는 그대로 거부한다
 * (실제로 curl이 {@code URL rejected: Malformed input to a URL function} 으로 실패했다).
 */
class GithubOAuthClientTest {

    private static final String SCOPE_WITH_SPACE = "user:email read:user";

    private GithubOAuthClient client(String scope) {
        GithubProperties properties = new GithubProperties(
                new GithubProperties.OAuthProperties(
                        "Ov23liTestClientId", "test-secret", "https://qeploy.com/api/v1/auth/github/callback", scope),
                new GithubProperties.AppProperties(
                        "1", "/tmp/none.pem", "https://qeploy.com/api/v1/auth/github/app/callback",
                        "webhook-secret", "Iv23liTestClientId", "app-secret")
        );
        return new GithubOAuthClient(properties);
    }

    @Test
    @DisplayName("scope의 공백이 인코딩되어 쿼리 문자열에 raw 공백이 남지 않는다")
    void authorizeUrlEncodesSpaceInScope() {
        String url = client(SCOPE_WITH_SPACE).getAuthorizeUrl("test-state");

        assertThat(url).doesNotContain(" ");
        assertThat(url).contains("scope=user:email%20read:user");
    }

    @Test
    @DisplayName("생성된 URL은 java.net.URI가 파싱할 수 있는 정규 URL이다")
    void authorizeUrlIsParseable() {
        String url = client(SCOPE_WITH_SPACE).getAuthorizeUrl("test-state");

        // URI.create 는 raw 공백이 있으면 IllegalArgumentException 을 던진다.
        // curl 이 거부했던 것과 같은 검증이다.
        URI parsed = URI.create(url);

        assertThat(parsed.getHost()).isEqualTo("github.com");
        assertThat(parsed.getPath()).isEqualTo("/login/oauth/authorize");
    }

    @Test
    @DisplayName("client_id와 state가 쿼리에 그대로 실린다")
    void authorizeUrlCarriesClientIdAndState() {
        String url = client(SCOPE_WITH_SPACE).getAuthorizeUrl("state-abc123");

        assertThat(url).contains("client_id=Ov23liTestClientId");
        assertThat(url).contains("state=state-abc123");
    }
}
