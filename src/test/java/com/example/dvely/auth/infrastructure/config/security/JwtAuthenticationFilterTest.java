package com.example.dvely.auth.infrastructure.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.apitoken.application.service.ApiTokenAuthenticator;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenClaims;
import com.example.dvely.auth.application.port.out.TokenPort;
import jakarta.servlet.FilterChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final String PAT = "qp_someopaquetokenvalue";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.payload.sig";

    private TokenPort tokenPort;
    private TokenBlacklistPort blacklist;
    private ApiTokenAuthenticator apiTokenAuthenticator;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        tokenPort = mock(TokenPort.class);
        blacklist = mock(TokenBlacklistPort.class);
        apiTokenAuthenticator = mock(ApiTokenAuthenticator.class);
        filter = new JwtAuthenticationFilter(tokenPort, blacklist, apiTokenAuthenticator);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static TokenClaims claims() {
        return new TokenClaims(42L, "jti-1", LocalDateTime.now().plusHours(1));
    }

    private static ApiToken token(ApiTokenScope scope) {
        return new ApiToken(1L, 7L, "hash", "qp_abcd1234", scope, null,
                LocalDateTime.now().plusDays(1), null, LocalDateTime.now());
    }

    private MockHttpServletResponse run(String method, String bearer) throws Exception {
        return run(method, "/api/v1/projects", bearer);
    }

    private MockHttpServletResponse run(String method, String path, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private static Long authenticatedUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    @Test
    void aPatIsRoutedByItsPrefixAndNeverParsedAsAJwt() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.READ)));

        run("GET", PAT);

        assertThat(authenticatedUserId()).isEqualTo(7L);
        // Parsing a PAT as a JWT would throw on every agent request; the prefix decides instead.
        verify(tokenPort, never()).parseClaims(anyString());
    }

    @Test
    void aJwtStillTakesTheOriginalPathUntouched() throws Exception {
        when(tokenPort.parseClaims(JWT)).thenReturn(claims());
        when(blacklist.isRevoked("jti-1")).thenReturn(false);

        run("GET", JWT);

        assertThat(authenticatedUserId()).isEqualTo(42L);
        verify(apiTokenAuthenticator, never()).authenticate(anyString());
    }

    @Test
    void aJwtIsParsedExactlyOncePerRequest() throws Exception {
        // 10-1 회귀 방지: userId 와 jti 를 따로 받던 시절엔 요청마다 서명 검증이 두 번 돌았다.
        when(tokenPort.parseClaims(JWT)).thenReturn(claims());
        when(blacklist.isRevoked("jti-1")).thenReturn(false);

        run("GET", JWT);

        verify(tokenPort, times(1)).parseClaims(JWT);
        verify(tokenPort, never()).getUserId(anyString());
        verify(tokenPort, never()).getJti(anyString());
    }

    @Test
    void aRevokedJwtLeavesTheRequestAnonymous() throws Exception {
        when(tokenPort.parseClaims(JWT)).thenReturn(claims());
        when(blacklist.isRevoked("jti-1")).thenReturn(true);

        run("GET", JWT);

        assertThat(authenticatedUserId()).isNull();
    }

    @Test
    void aReadTokenMayRead() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.READ)));

        MockHttpServletResponse response = run("GET", PAT);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aReadTokenIsRefusedOnWriteMethodsWithA403() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.READ)));

        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            SecurityContextHolder.clearContext();
            MockHttpServletResponse response = run(method, PAT);

            // 403 rather than 401: the caller authenticated fine and simply lacks the scope, so
            // sending them to re-authenticate would be a dead end.
            assertThat(response.getStatus())
                    .withFailMessage("%s 가 403 이어야 합니다", method)
                    .isEqualTo(403);
            assertThat(response.getContentAsString()).contains("FORBIDDEN");
        }
    }

    @Test
    void aScopeRejectionStopsTheChainInsteadOfFallingThroughAsAnonymous() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.READ)));

        run("POST", PAT);

        // Continuing would hand the request to endpoints as an unauthenticated caller, turning a
        // clear scope error into a confusing 401 somewhere else.
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aWriteTokenMayMakeTheWritesAgentsAreGiven() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        for (String call : new String[] {
                "POST /api/v1/projects/3/deployments",
                "POST /api/v1/deployments/12/retry",
                "POST /api/v1/projects/3/environment-variables",
                "PATCH /api/v1/projects/3/environment-variables/40",
                "POST /api/v1/projects/3/domains"}) {
            SecurityContextHolder.clearContext();
            String[] parts = call.split(" ");

            MockHttpServletResponse response = run(parts[0], parts[1], PAT);

            assertThat(response.getStatus()).withFailMessage("%s 가 통과해야 합니다", call).isEqualTo(200);
            assertThat(authenticatedUserId()).isEqualTo(7L);
        }
    }

    @Test
    void aWriteTokenIsRefusedOnWritesThatBelongToAPerson() throws Exception {
        // #453: before the allowlist, these all went through with a WRITE token. The agent tools
        // just never called them, and nothing on the server said no — so an agent with a shell
        // could approve its own approval request, or mint itself a token that outlives this one.
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        for (String call : new String[] {
                "POST /api/v1/approvals/5/approve",
                "POST /api/v1/approvals/5/reject",
                "POST /api/v1/api-tokens",
                "DELETE /api/v1/api-tokens/9",
                "PUT /api/v1/ai-credentials/OPENAI",
                "POST /api/v1/cloud-connections",
                "DELETE /api/v1/cloud-connections/2",
                "DELETE /api/v1/projects/3",
                "POST /api/v1/servers/8/terminate",
                "PUT /api/v1/projects/3/settings/cost-budget"}) {
            SecurityContextHolder.clearContext();
            String[] parts = call.split(" ");

            MockHttpServletResponse response = run(parts[0], parts[1], PAT);

            assertThat(response.getStatus()).withFailMessage("%s 가 403 이어야 합니다", call).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("FORBIDDEN");
            assertThat(authenticatedUserId()).isNull();
        }
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theAllowlistMatchesWholePathsOnly() throws Exception {
        // A prefix or suffix match would let a neighbouring endpoint ride on an allowed one.
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        for (String path : new String[] {
                "/api/v1/projects/3/deployments/extra",
                "/api/v1/projects/3/domainsX",
                "/evil/api/v1/projects/3/deployments",
                "/api/v1/projects/abc/deployments"}) {
            SecurityContextHolder.clearContext();

            assertThat(run("POST", path, PAT).getStatus())
                    .withFailMessage("%s 가 403 이어야 합니다", path).isEqualTo(403);
        }
    }

    @Test
    void aBrowserLoginIsNotNarrowedByThePatAllowlist() throws Exception {
        when(tokenPort.parseClaims(JWT)).thenReturn(claims());
        when(blacklist.isRevoked("jti-1")).thenReturn(false);

        MockHttpServletResponse response = run("POST", "/api/v1/approvals/5/approve", JWT);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authenticatedUserId()).isEqualTo(42L);
    }

    @Test
    void everyWriteTheAgentClientMakesIsAllowed() throws Exception {
        // The allowlist is hand-kept beside agent-tools. If the client grows a write and nobody adds
        // it here, MCP breaks with a 403 that only shows up in a user's terminal — so read the
        // client's own source and check each write it makes.
        //
        // Token issuance is excluded on purpose: the CLI calls it with a browser JWT, never a PAT.
        String client = Files.readString(Path.of("agent-tools/packages/client/src/index.js"));
        // The path is a template literal whose ${...} may themselves hold quotes — pathId('projectId', …)
        // since #482 — so a ${...} is consumed whole rather than ended at the first quote.
        Matcher m = Pattern.compile(
                "this\\.(?:(post|patch|put|delete)\\(|request\\('(POST|PATCH|PUT|DELETE)',\\s*)"
                        + "(?:`((?:[^`$]|\\$\\{[^}]*})*)`|'([^']+)')")
                .matcher(client);
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        int checked = 0;
        while (m.find()) {
            String method = (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase();
            String path = (m.group(3) != null ? m.group(3) : m.group(4)).replaceAll("\\$\\{[^}]+}", "1");
            if (path.startsWith("/api/v1/api-tokens")) {
                continue;
            }
            SecurityContextHolder.clearContext();

            assertThat(run(method, path, PAT).getStatus())
                    .withFailMessage("agent-tools 가 %s %s 를 쓰는데 허용 목록에 없습니다", method, path)
                    .isEqualTo(200);
            checked++;
        }
        // An empty loop would pass while proving nothing — e.g. if the client's call style changed.
        assertThat(checked).isEqualTo(5);
    }

    @Test
    void anUnknownOrExpiredPatLeavesTheRequestAnonymousAndContinues() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.empty());

        MockHttpServletResponse response = run("GET", PAT);

        // Unknown, expired and revoked are indistinguishable to the caller by design.
        assertThat(authenticatedUserId()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noAuthorizationHeaderIsSimplyAnonymous() throws Exception {
        run("GET", null);

        assertThat(authenticatedUserId()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void grantsAScopeAuthorityAlongsideRoleUser() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        run("GET", PAT);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_USER", "SCOPE_WRITE");
    }
}
