package com.example.dvely.config;

import jakarta.servlet.DispatcherType;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.infrastructure.config.security.JwtAuthenticationFilter;
import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.common.response.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenPort tokenPort,
            TokenBlacklistPort tokenBlacklistPort,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 가 쓰는 <b>비동기 디스패치</b>는 인가 검사에서 제외한다.
                        //
                        // Spring Security 6.1+ 는 REQUEST 뿐 아니라 ASYNC·FORWARD·ERROR 디스패치도
                        // 필터한다. SseEmitter 는 최초 요청에서 응답 헤더를 내보낸 뒤 비동기 디스패치로
                        // 이어지는데, 그 디스패치는 컨테이너 스레드에서 새로 시작돼 SecurityContext 가
                        // 없다. 그래서 이미 인증을 통과한 요청이 두 번째 관문에서 거부된다.
                        //
                        // 증상이 고약하다. 응답은 이미 커밋된 뒤라 401 본문조차 못 쓰고 로그에만
                        // 남는다(2026-09-07~08 dev 에서 계속 찍히던 것):
                        //   AuthorizationDeniedException: Access Denied
                        //   Unable to handle the Spring Security Exception because the response is
                        //   already committed
                        // FE 에는 스트림이 401 로 끊긴 것으로 보이고, 폴백인 5초 폴링으로 내려앉는다 —
                        // 화면은 안 깨지므로 진행 표시가 실시간이 아니게 된 것을 아무도 모른다.
                        //
                        // 최초 REQUEST 디스패치는 그대로 인가를 거치므로 보호 범위는 줄지 않는다.
                        // ASYNC 만 연다 — FORWARD/INCLUDE 는 그대로 인가를 거친다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 인증 없이 접근 가능한 Auth 엔드포인트
                        .requestMatchers(
                                "/api/v1/auth/github/url",
                                "/api/v1/auth/github/callback",
                                "/api/v1/auth/github/app/callback",
                                "/api/v1/auth/refresh",
                                "/api/v1/webhook/github",
                                "/api/v1/previews/**",
                                "/api/v1/tls/allow"
                        ).permitAll()
                        // 템플릿 카탈로그. 내용 자체가 이미 공개 Pages 에 있는 정적 목록이고 사용자
                        // 데이터가 없다. 로그인 전 화면에서도 갤러리를 띄울 수 있게 열어둔다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/templates", "/api/v1/templates/*").permitAll()
                        // Swagger UI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // Spring Boot 에러 핸들링 경로 (404 등이 정상 동작하려면 필요)
                        .requestMatchers("/error").permitAll()
                        // 배포 워크플로의 기동 확인용. show-details: never 라 상태값만 나간다.
                        .requestMatchers("/actuator/health").permitAll()
                        // 나머지는 JWT 인증 필요
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeError(
                                response,
                                objectMapper,
                                ErrorCode.UNAUTHORIZED
                        ))
                        .accessDeniedHandler((request, response, ex) -> writeError(
                                response,
                                objectMapper,
                                ErrorCode.FORBIDDEN
                        ))
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokenPort, tokenBlacklistPort),
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedOriginPatterns(corsProperties.allowedOriginPatterns());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        // 프리뷰 게이트웨이 전용 CORS (Issue #108). CSP sandbox(#102)로 불투명 오리진이 된
        // 프리뷰 문서의 module script 는 Origin: null 로 오는데, 위 FE 오리진 목록 기반
        // 설정에 걸리면 컨트롤러 도달 전에 403 으로 거절되어 프리뷰가 백지가 된다. 이
        // 경로의 자격은 쿠키가 아니라 URL 의 회전 accessToken 이므로 credentials 없이
        // 전부 연다 — 'null' 오리진은 누구나 sandbox iframe 으로 만들 수 있어 목록으로
        // 좁혀도 효과가 없다. 등록 순서 유의: 먼저 등록해야 "/**" 보다 우선한다.
        CorsConfiguration previewConfig = new CorsConfiguration();
        previewConfig.setAllowedOrigins(List.of(CorsConfiguration.ALL));
        // 읽기(GET/모듈 스크립트)뿐 아니라 쓰기(POST/PUT/DELETE/PATCH)도 허용 — 에이전트가 만든 앱의
        // 등록·폼이 불투명 오리진 프레임 안에서 자기 백엔드로 쓰려면 필요하다. application/json 은 단순
        // 요청이 아니라 프리플라이트(OPTIONS)가 오는데, 아래 메서드·헤더 허용으로 통과한다. 자격은 쿠키가
        // 아니라 URL 회전 토큰이라 credentials 없이 연다(위 GET 주석과 동일 근거).
        previewConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        previewConfig.setAllowedHeaders(List.of("*"));
        previewConfig.setAllowCredentials(false);
        previewConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/previews/**", previewConfig);
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeError(HttpServletResponse response,
                            ObjectMapper objectMapper,
                            ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }
}
