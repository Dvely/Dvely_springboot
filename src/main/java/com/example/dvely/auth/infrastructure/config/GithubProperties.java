package com.example.dvely.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public record GithubProperties(
        OAuthProperties oauth,
        AppProperties app
) {
    public record OAuthProperties(
            String clientId,
            String clientSecret,
            String redirectUri,
            String scope
    ) {}

    public record AppProperties(
            String appId,
            String privateKey,
            String installationRedirectUri
    ) {}
}
