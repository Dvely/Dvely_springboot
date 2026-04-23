package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubUserPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GithubUserClient implements GithubUserPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    /**
     * OAuth User Access Token으로 GitHub 유저 정보 조회
     * GET https://api.github.com/user
     */
    @Override
    public GithubUserInfo getUser(String accessToken) {
        GithubUserResponse response;
        try {
            response = RestClient.create()
                    .get()
                    .uri(GITHUB_API_BASE_URL + "/user")
                    .header("Authorization", "token " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(GithubUserResponse.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new IllegalStateException(
                    "GitHub 유저 정보 조회 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e
            );
        }

        if (response == null) {
            throw new IllegalStateException("GitHub 유저 정보 조회 실패: 응답 없음");
        }

        return new GithubUserInfo(String.valueOf(response.id()), response.login(), response.avatarUrl());
    }

    private record GithubUserResponse(
            @JsonProperty("id") long id,
            @JsonProperty("login") String login,
            @JsonProperty("name") String name,
            @JsonProperty("email") String email,
            @JsonProperty("avatar_url") String avatarUrl
    ) {}
}
