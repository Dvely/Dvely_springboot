package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.auth.infrastructure.persistence.entity.InstallationTokenEntity;
import com.example.dvely.auth.infrastructure.persistence.repository.SpringDataInstallationTokenRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubAppClient implements GithubAppPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_BASE_URL = "https://github.com";

    private final GithubProperties properties;
    private final SpringDataInstallationTokenRepository installationTokenRepository;

    /**
     * OAuth 유저 토큰으로 해당 유저의 GitHub App 설치 ID 조회
     * GET /user/installations
     *
     * 반환값: 우리 앱(appId 기준)의 installation ID, 없으면 empty
     */
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
            log.warn("GitHub App 설치 조회 실패 (App이 설치되지 않았을 수 있음): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Installation Access Token 발급
     * POST /app/installations/{installation_id}/access_tokens
     *
     * GitHub App JWT로 인증 → 유효 1시간짜리 토큰 발급
     * 이 토큰으로 해당 installation의 레포, 이슈 등에 접근 가능
     */
    @Override
    @Transactional
    public String getInstallationToken(long installationId) {
        // 캐시된 토큰이 유효하면 재사용
        Optional<InstallationTokenEntity> cached = installationTokenRepository.findByInstallationId(installationId);
        if (cached.isPresent() && cached.get().isValid()) {
            return cached.get().getToken();
        }

        // 없거나 만료 임박 → GitHub에서 새로 발급
        InstallationTokenResponse response = issueInstallationToken(installationId);

        LocalDateTime expiresAt = Instant.parse(response.expiresAt())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // upsert
        InstallationTokenEntity entity = cached
                .orElseGet(() -> new InstallationTokenEntity(installationId, response.token(), expiresAt));
        entity.update(response.token(), expiresAt);
        installationTokenRepository.save(entity);

        return response.token();
    }

    private InstallationTokenResponse issueInstallationToken(long installationId) {
        InstallationTokenResponse response = RestClient.create()
                .post()
                .uri(GITHUB_API_BASE_URL + "/app/installations/{id}/access_tokens", installationId)
                .header("Authorization", "Bearer " + generateAppJwt())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(InstallationTokenResponse.class);

        if (response == null || response.token() == null) {
            throw new IllegalStateException("Installation Access Token 발급 실패: installation_id=" + installationId);
        }

        return response;
    }

    /**
     * GitHub App 설치 페이지 URL 생성
     *
     * state 파라미터에 서비스 JWT를 담아 전달
     * GitHub이 설치 완료 후 콜백 URL로 리다이렉트할 때 state를 그대로 돌려줌
     * → 콜백에서 state(JWT) 검증으로 어떤 유저가 설치했는지 식별
     *
     * GitHub App 설정의 Callback URL과 installationRedirectUri가 일치해야 함
     */
    @Override
    public String getInstallationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString(GITHUB_BASE_URL + "/apps/" + getAppSlug() + "/installations/new")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    /**
     * GitHub App JWT 생성 (RS256, 유효 9분)
     *
     * GitHub App 인증 흐름:
     * 1. App Private Key(RSA)로 JWT 서명 → GitHub App JWT
     * 2. GitHub App JWT로 Installation Access Token 발급
     * 3. Installation Access Token으로 실제 API 호출
     */
    private String generateAppJwt() {
        PrivateKey privateKey = loadPrivateKey();
        Instant now = Instant.now();

        return Jwts.builder()
                .issuer(properties.app().appId())
                .issuedAt(Date.from(now.minusSeconds(60)))   // 60초 여유 (GitHub 클럭 드리프트 대비)
                .expiration(Date.from(now.plusSeconds(540))) // 9분 (GitHub 최대 10분)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * GitHub App Private Key(PEM) 로드
     * 파일 경로 또는 PEM 문자열 직접 지원
     * GitHub에서 다운받은 PKCS1(RSA) 형식 처리
     */
    private PrivateKey loadPrivateKey() {
        try {
            String pemContent = properties.app().privateKey();

            // 파일 경로인 경우 파일에서 읽기
            if (!pemContent.trim().startsWith("-----BEGIN")) {
                pemContent = Files.readString(Path.of(pemContent.trim()));
            }

            try (PEMParser parser = new PEMParser(new StringReader(pemContent))) {
                Object obj = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

                if (obj instanceof PEMKeyPair keyPair) {
                    // PKCS1 형식 (-----BEGIN RSA PRIVATE KEY-----)
                    return converter.getKeyPair(keyPair).getPrivate();
                } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo keyInfo) {
                    // PKCS8 형식 (-----BEGIN PRIVATE KEY-----)
                    return converter.getPrivateKey(keyInfo);
                }

                throw new IllegalStateException("지원하지 않는 PEM 키 형식입니다");
            }
        } catch (IOException e) {
            throw new IllegalStateException("GitHub App Private Key 로드 실패", e);
        }
    }

    /**
     * App Slug 조회 (설치 URL 구성용)
     * GET /app
     */
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

    private record InstallationTokenResponse(
            @JsonProperty("token") String token,
            @JsonProperty("expires_at") String expiresAt
    ) {}

    private record AppInfoResponse(
            @JsonProperty("id") long id,
            @JsonProperty("slug") String slug,
            @JsonProperty("name") String name
    ) {}
}
