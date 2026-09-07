package com.example.dvely.auth.infrastructure.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.apitoken.application.service.ApiTokenAuthenticator;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import jakarta.servlet.FilterChain;
import java.time.LocalDateTime;
import java.util.Optional;
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

    private static ApiToken token(ApiTokenScope scope) {
        return new ApiToken(1L, 7L, "hash", "qp_abcd1234", scope, null,
                LocalDateTime.now().plusDays(1), null, LocalDateTime.now());
    }

    private MockHttpServletResponse run(String method, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/projects");
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
        verify(tokenPort, never()).getUserId(anyString());
    }

    @Test
    void aJwtStillTakesTheOriginalPathUntouched() throws Exception {
        when(tokenPort.getUserId(JWT)).thenReturn(42L);
        when(tokenPort.getJti(JWT)).thenReturn("jti-1");
        when(blacklist.isRevoked("jti-1")).thenReturn(false);

        run("GET", JWT);

        assertThat(authenticatedUserId()).isEqualTo(42L);
        verify(apiTokenAuthenticator, never()).authenticate(anyString());
    }

    @Test
    void aRevokedJwtLeavesTheRequestAnonymous() throws Exception {
        when(tokenPort.getUserId(JWT)).thenReturn(42L);
        when(tokenPort.getJti(JWT)).thenReturn("jti-1");
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
    void aWriteTokenMayWrite() throws Exception {
        when(apiTokenAuthenticator.authenticate(PAT)).thenReturn(Optional.of(token(ApiTokenScope.WRITE)));

        MockHttpServletResponse response = run("POST", PAT);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authenticatedUserId()).isEqualTo(7L);
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
