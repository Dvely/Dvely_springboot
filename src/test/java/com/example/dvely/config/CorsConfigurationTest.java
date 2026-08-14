package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CorsConfigurationTest {

    @Test
    void usesConfiguredOriginsAndPatterns() {
        CorsProperties properties = new CorsProperties(
                List.of(" http://localhost:5173 ", ""),
                List.of("https://*.qeploy.com")
        );
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(configuration.getAllowedOriginPatterns()).containsExactly("https://*.qeploy.com");
        assertThat(configuration.getAllowedMethods())
                .containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }

    /**
     * 프리뷰 게이트웨이 전용 CORS (Issue #108). CSP sandbox 로 불투명 오리진이 된 프리뷰
     * 문서의 module script 는 Origin: null 로 오는데, FE 오리진 목록 설정에 걸리면 컨트롤러
     * 도달 전에 403 이 되어 프리뷰가 백지가 된다. 이 경로의 자격은 URL 의 회전 accessToken
     * 이므로 credentials 없이 전부 연다.
     */
    @Test
    void opensThePreviewGatewayToTheOpaqueOriginTheSandboxCreates() {
        CorsProperties properties = new CorsProperties(List.of("https://qeploy.com"), List.of());
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(properties);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/previews/session-1/token-1/assets/index.js");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        // 'null' 오리진(불투명)까지 허용된다는 실동작 보증 — 이게 통과 못 하면 프리뷰가 백지다.
        assertThat(configuration.checkOrigin("null")).isEqualTo("*");
        // 쿠키 기반 CORS 가 아니다 — credentials 를 허용하면 '*' 를 쓸 수 없게 되고, 열 이유도 없다.
        assertThat(configuration.getAllowCredentials()).isFalse();
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "OPTIONS");
    }

    /** previews 전용 설정이 다른 API 경로의 FE 오리진·credentials 정책을 건드리면 안 된다. */
    @Test
    void keepsTheCredentialedFeConfigurationForOtherApiPaths() {
        CorsProperties properties = new CorsProperties(List.of("https://qeploy.com"), List.of());
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://qeploy.com");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.checkOrigin("null")).isNull();
    }
}
