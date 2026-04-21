package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubAppClient implements GithubAppPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_BASE_URL = "https://github.com";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final GithubProperties properties;

    @Override
    public Optional<Long> findInstallationId(String oauthToken) {
        try {
            UserInstallationsResponse response = RestClient.create()
                    .get()
                    .uri(GITHUB_API_BASE_URL + "/user/installations")
                    .header("Authorization", "token " + oauthToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(UserInstallationsResponse.class);

            if (response == null || response.installations() == null) {
                return Optional.empty();
            }

            long appId = Long.parseLong(properties.app().appId());
            return response.installations().stream()
                    .filter(i -> i.appId() == appId)
                    .map(InstallationDto::id)
                    .findFirst();

        } catch (RestClientException e) {
            log.warn("GitHub App 설치 조회 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String getInstallationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString(GITHUB_BASE_URL + "/apps/" + getAppSlug() + "/installations/new")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    /**
     * GitHub App User Token 발급
     * 설치 콜백의 code → access_token(8h) + refresh_token(6개월)
     */
    @Override
    public GithubUserTokenInfo getUserToken(String code) {
        UserTokenResponse response = exchangeToken(Map.of(
                "client_id", properties.app().clientId(),
                "client_secret", properties.app().clientSecret(),
                "code", code
        ));
        return toTokenInfo(response);
    }

    /**
     * GitHub App User Token 갱신
     * refresh_token → 새 access_token + 새 refresh_token
     */
    @Override
    public GithubUserTokenInfo refreshUserToken(String refreshToken) {
        UserTokenResponse response = exchangeToken(Map.of(
                "client_id", properties.app().clientId(),
                "client_secret", properties.app().clientSecret(),
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        ));
        return toTokenInfo(response);
    }

    private UserTokenResponse exchangeToken(Map<String, String> body) {
        UserTokenResponse response = RestClient.create()
                .post()
                .uri(GITHUB_TOKEN_URL)
                .header("Accept", "application/json")
                .body(body)
                .retrieve()
                .body(UserTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("GitHub App User Token 발급/갱신 실패");
        }
        return response;
    }

    private GithubUserTokenInfo toTokenInfo(UserTokenResponse response) {
        return new GithubUserTokenInfo(
                response.accessToken(),
                response.refreshToken(),
                response.expiresIn(),
                response.refreshTokenExpiresIn()
        );
    }

    // App JWT - 설치 URL의 slug 조회에만 사용
    private String generateAppJwt() {
        PrivateKey privateKey = loadPrivateKey();
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.app().appId())
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(540)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey loadPrivateKey() {
        try {
            String pemContent = properties.app().privateKey();
            if (!pemContent.trim().startsWith("-----BEGIN")) {
                pemContent = Files.readString(Path.of(pemContent.trim()));
            }
            try (PEMParser parser = new PEMParser(new StringReader(pemContent))) {
                Object obj = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                if (obj instanceof PEMKeyPair keyPair) {
                    return converter.getKeyPair(keyPair).getPrivate();
                } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo keyInfo) {
                    return converter.getPrivateKey(keyInfo);
                }
                throw new IllegalStateException("지원하지 않는 PEM 키 형식입니다");
            }
        } catch (IOException e) {
            throw new IllegalStateException("GitHub App Private Key 로드 실패", e);
        }
    }

    private String getAppSlug() {
        try {
            AppInfoResponse response = RestClient.create()
                    .get()
                    .uri(GITHUB_API_BASE_URL + "/app")
                    .header("Authorization", "Bearer " + generateAppJwt())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(AppInfoResponse.class);
            return response != null ? response.slug() : properties.app().appId();
        } catch (Exception e) {
            log.warn("App slug 조회 실패, appId 사용: {}", e.getMessage());
            return properties.app().appId();
        }
    }

    // ---- Response DTOs ----

    private record UserInstallationsResponse(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("installations") List<InstallationDto> installations
    ) {}

    private record InstallationDto(
            @JsonProperty("id") long id,
            @JsonProperty("app_id") long appId,
            @JsonProperty("account") AccountDto account
    ) {}

    private record AccountDto(
            @JsonProperty("login") String login,
            @JsonProperty("type") String type
    ) {}

    private record UserTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_token_expires_in") long refreshTokenExpiresIn,
            @JsonProperty("token_type") String tokenType
    ) {}

    private record AppInfoResponse(
            @JsonProperty("id") long id,
            @JsonProperty("slug") String slug,
            @JsonProperty("name") String name
    ) {}
}
