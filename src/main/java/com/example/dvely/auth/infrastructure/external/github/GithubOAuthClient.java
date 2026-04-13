package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class GithubOAuthClient implements GithubOAuthPort {

    private static final String GITHUB_BASE_URL = "https://github.com";
    private static final String AUTHORIZE_PATH = "/login/oauth/authorize";
    private static final String TOKEN_PATH = "/login/oauth/access_token";

    private final GithubProperties properties;

    /**
     * OAuth App 인증 URL 생성
     * 유저가 이 URL로 이동 → GitHub 로그인 → redirect_uri로 code 전달
     */
    @Override
    public String getAuthorizeUrl() {
        return UriComponentsBuilder
                .fromUriString(GITHUB_BASE_URL + AUTHORIZE_PATH)
                .queryParam("client_id", properties.oauth().clientId())
                .queryParam("redirect_uri", properties.oauth().redirectUri())
                .queryParam("scope", properties.oauth().scope())
                // TODO: state 파라미터 추가 (CSRF 방지) - Redis 세션으로 구현 권장
                .build()
                .toUriString();
    }

    /**
     * Authorization Code → OAuth User Access Token 교환
     * POST https://github.com/login/oauth/access_token
     */
    @Override
    public String getAccessToken(String code) {
        AccessTokenResponse response;
        try {
            response = RestClient.create()
                    .post()
                    .uri(GITHUB_BASE_URL + TOKEN_PATH)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .body(new AccessTokenRequest(
                            properties.oauth().clientId(),
                            properties.oauth().clientSecret(),
                            code
                    ))
                    .retrieve()
                    .body(AccessTokenResponse.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // GitHub API가 4xx/5xx 반환한 경우
            // 주로 client_id/secret이 잘못됐거나 code가 만료된 경우
            throw new IllegalStateException(
                    "GitHub OAuth 토큰 발급 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e
            );
        }

        if (response == null || response.accessToken() == null) {
            // GitHub는 오류 시 JSON body에 error 필드를 담아 200으로 응답하기도 함
            String errorMsg = (response != null && response.error() != null)
                    ? response.error() + " - " + response.errorDescription()
                    : "응답 없음";
            throw new IllegalStateException("GitHub OAuth 토큰 발급 실패: " + errorMsg);
        }

        return response.accessToken();
    }

    private record AccessTokenRequest(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret,
            @JsonProperty("code") String code
    ) {}

    private record AccessTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("scope") String scope,
            @JsonProperty("error") String error,
            @JsonProperty("error_description") String errorDescription
    ) {}
}
