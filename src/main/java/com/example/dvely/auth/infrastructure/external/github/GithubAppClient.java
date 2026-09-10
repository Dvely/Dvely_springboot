package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class GithubAppClient implements GithubAppPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_BASE_URL = "https://github.com";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final GithubProperties properties;
    private final RestClient githubRestClient;
    private final InstallationTokenCache installationTokens = new InstallationTokenCache();

    /**
     * PEM 파싱 + RSA 키 복원 결과. 키는 프로세스 수명 동안 바뀌지 않으므로 처음 쓸 때 한 번만
     * 만든다 — 예전에는 App JWT 를 만들 때마다 BouncyCastle 로 다시 파싱했다.
     *
     * <p>생성자에서 미리 읽지 않는 것은 의도다. 지금은 키가 잘못돼 있어도 앱은 뜨고 첫 GitHub App
     * 호출에서 드러나는데, 성능 작업이 기동 실패 조건까지 함께 바꾸면 배포가 안 뜰 때 원인이
     * 어느 쪽인지 가리기 어려워진다.</p>
     */
    private volatile PrivateKey privateKey;

    // 생성자를 직접 쓰는 이유: RestClient 빈이 여럿이라 타입만으로는 고를 수 없는데,
    // Lombok 은 필드의 @Qualifier 를 생성자 파라미터로 옮겨주지 않는다.
    public GithubAppClient(GithubProperties properties,
                           @Qualifier("githubRestClient") RestClient githubRestClient) {
        this.properties = properties;
        this.githubRestClient = githubRestClient;
    }

    @Override
    public Optional<Long> findInstallationId(String oauthToken) {
        try {
            UserInstallationsResponse response = githubRestClient
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
                // state는 서비스 JWT라 '.'만 들어가지만, 값의 형식이 바뀌어도 안전하도록
                // 인코딩을 거친다. GithubOAuthClient.getAuthorizeUrl 과 같은 이유다.
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public String getReauthorizeUrl(Long installationId, String state) {
        return UriComponentsBuilder
                .fromUriString(GITHUB_BASE_URL + "/login/oauth/authorize")
                .queryParam("client_id", properties.app().clientId())
                .queryParam("state", state)
                .build()
                .encode()
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
        UserTokenResponse response = githubRestClient
                .post()
                .uri(GITHUB_TOKEN_URL)
                .header("Accept", "application/json")
                .body(body)
                .retrieve()
                .body(UserTokenResponse.class);

        if (response == null) {
            throw new IllegalStateException("GitHub App User Token 발급/갱신 실패: 응답 없음");
        }
        if (response.error() != null) {
            throw new IllegalStateException("GitHub App User Token 발급/갱신 실패: " + response.error()
                    + (response.errorDescription() != null ? " - " + response.errorDescription() : ""));
        }
        if (response.accessToken() == null) {
            throw new IllegalStateException("GitHub App User Token 발급/갱신 실패: access_token 없음");
        }
        return response;
    }

    private GithubUserTokenInfo toTokenInfo(UserTokenResponse response) {
        return new GithubUserTokenInfo(
                response.accessToken(),
                response.refreshToken(),
                response.expiresIn() != null ? response.expiresIn() : 28800L,
                response.refreshTokenExpiresIn() != null ? response.refreshTokenExpiresIn() : 15897600L
        );
    }

    @Override
    public String getInstallationToken(Long installationId) {
        Instant now = Instant.now();
        return installationTokens.find(installationId, now)
                .orElseGet(() -> issueInstallationToken(installationId, now));
    }

    /**
     * 401 을 받았을 때 캐시를 비우고 재발급하는 경로는 두지 않았다.
     *
     * <p>캐시는 남은 수명이 5 분 미만인 토큰을 건네지 않고, U1 이후 이 저장소의 GitHub 호출은
     * 최대 60 초로 묶인다 — 건네진 토큰이 쓰이는 도중 만료되는 경우가 없다. 남는 401 의 원인은
     * App 삭제·권한 회수·토큰 폐기인데, 그건 재발급으로 풀리지 않고 실패한 호출만 두 배가 된다.
     * 설치를 중지했다 푸는 드문 경우에는 캐시된 토큰이 최대 55 분간 401 을 내지만, 그동안에도
     * 사용자에게는 같은 오류가 보일 뿐이고 시간이 지나면 저절로 풀린다.</p>
     *
     * <p>동시에 두 스레드가 미스를 내면 발급이 두 번 일어난다. GitHub 은 이를 허용하고 나중 것이
     * 캐시에 남으므로 그대로 둔다 — 막으려면 발급하는 네트워크 호출 동안 잠금을 쥐어야 한다.</p>
     */
    private String issueInstallationToken(Long installationId, Instant now) {
        InstallationTokenResponse response = githubRestClient
                .post()
                .uri(GITHUB_API_BASE_URL + "/app/installations/" + installationId + "/access_tokens")
                .header("Authorization", "Bearer " + generateAppJwt())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(InstallationTokenResponse.class);
        if (response == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalStateException("Installation Access Token 발급 실패");
        }
        installationTokens.put(installationId, response.token(), response.expiresAtOrNull(), now);
        return response.token();
    }

    // App JWT - 설치 URL의 slug 조회에만 사용
    private String generateAppJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.app().appId())
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(540)))
                .signWith(privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey privateKey() {
        PrivateKey loaded = privateKey;
        if (loaded == null) {
            synchronized (this) {
                loaded = privateKey;
                if (loaded == null) {
                    loaded = loadPrivateKey();
                    privateKey = loaded;
                }
            }
        }
        return loaded;
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
            AppInfoResponse response = githubRestClient
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
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_token_expires_in") Long refreshTokenExpiresIn,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("error") String error,
            @JsonProperty("error_description") String errorDescription
    ) {
        // record 기본 toString 은 전 필드를 찍는다 — 이 값이 로그·예외에 실리면 액세스/리프레시
        // 토큰이 그대로 새어 나간다.
        @Override
        public String toString() {
            return "UserTokenResponse(error=" + error + ")";
        }
    }

    private record InstallationTokenResponse(
            @JsonProperty("token") String token,
            @JsonProperty("expires_at") String expiresAt
    ) {
        @Override
        public String toString() {
            return "InstallationTokenResponse(expiresAt=" + expiresAt + ")";
        }

        /** 읽지 못하면 null 을 준다 — 캐시가 문서상 수명(1 시간)으로 대신 잡는다. */
        Instant expiresAtOrNull() {
            if (expiresAt == null || expiresAt.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(expiresAt);
            } catch (DateTimeParseException e) {
                log.warn("installation token 의 expires_at 을 읽지 못해 기본 수명으로 캐시한다");
                return null;
            }
        }
    }

    private record AppInfoResponse(
            @JsonProperty("id") long id,
            @JsonProperty("slug") String slug,
            @JsonProperty("name") String name
    ) {}
}
