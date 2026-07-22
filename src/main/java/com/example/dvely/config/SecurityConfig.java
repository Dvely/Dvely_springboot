package com.example.dvely.config;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.infrastructure.config.security.JwtAuthenticationFilter;
import com.example.dvely.common.response.ApiResponse;
import com.example.dvely.common.response.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        // 인증 없이 접근 가능한 Auth 엔드포인트
                        .requestMatchers(
                                "/api/v1/auth/github/url",
                                "/api/v1/auth/github/callback",
                                "/api/v1/auth/github/app/callback",
                                "/api/v1/auth/refresh",
                                "/api/v1/webhook/github",
                                "/api/v1/previews/**"
                        ).permitAll()
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

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
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
