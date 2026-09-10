package com.example.dvely.auth.infrastructure.config.security;

import com.example.dvely.apitoken.application.service.ApiTokenAuthenticator;
import com.example.dvely.apitoken.domain.model.ApiToken;
import com.example.dvely.apitoken.domain.service.ApiTokenGenerator;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates a request from either a browser JWT or an agent/CLI personal access token.
 *
 * <p>The two are told apart by the PAT's {@code qp_} prefix rather than by attempting a JWT parse
 * first: parsing a PAT as a JWT would throw on every agent request, and "it threw, so try the other
 * one" is the kind of control flow that quietly starts accepting the wrong thing.</p>
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Methods a {@code READ} token may not use.
     *
     * <p>Scope is enforced by HTTP method rather than per-endpoint annotations, so a newly added
     * endpoint is covered the day it is written instead of the day someone remembers to annotate
     * it. It is coarse on purpose — the fine-grained narrowing lives in which operations are
     * exposed as agent tools at all.</p>
     */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final TokenPort tokenPort;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final ApiTokenAuthenticator apiTokenAuthenticator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (ApiTokenGenerator.looksLikeApiToken(token)) {
                if (!authenticateWithApiToken(token, request, response)) {
                    return; // 403 already written — a scope failure must not fall through as anonymous
                }
            } else {
                authenticateWithJwt(token);
            }
        }

        filterChain.doFilter(request, response);
    }

    /** @return false when the response has already been completed (scope rejection). */
    private boolean authenticateWithApiToken(String token,
                                             HttpServletRequest request,
                                             HttpServletResponse response) throws IOException {
        Optional<ApiToken> found = apiTokenAuthenticator.authenticate(token);
        if (found.isEmpty()) {
            // Unknown, expired and revoked all look identical here on purpose.
            return true;
        }
        ApiToken apiToken = found.get();

        if (WRITE_METHODS.contains(request.getMethod()) && !apiToken.allowsWrite()) {
            // 403 rather than falling through to anonymous: the caller authenticated correctly and
            // simply lacks the scope, and a 401 would send them off to re-authenticate for nothing.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"FORBIDDEN\",\"message\":\"이 토큰은 읽기 전용입니다. write 스코프 토큰이 필요합니다.\"}");
            return false;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        apiToken.getUserId(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"),
                                new SimpleGrantedAuthority("SCOPE_" + apiToken.getScope().name()))));
        return true;
    }

    private void authenticateWithJwt(String token) {
        try {
            Long userId = tokenPort.getUserId(token);
            String jti = tokenPort.getJti(token);

            if (tokenBlacklistPort.isRevoked(jti)) {
                return;
            }

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        } catch (Exception ignored) {
            // 유효하지 않은 토큰 — SecurityContext 비워둠 (인증 없음으로 처리)
        }
    }
}
