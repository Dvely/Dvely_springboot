package com.example.dvely.project.infrastructure.github;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class GithubProjectClient implements GithubRepositoryPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String PREVIEW_BRANCH = "preview";
    private static final int REPOSITORY_PAGE_SIZE = 100;
    private static final int MAX_REPOSITORY_PAGES = 10;

    private final UserRepository userRepository;
    private final GithubAppPort githubAppPort;
    private final GithubProperties githubProperties;
    private final RestClient restClient = RestClient.create();

    @Override
    public List<GithubRepository> listRepositories(Long ownerUserId) {
        try {
            String token = getGithubInstallationAccessToken(ownerUserId);
            List<GithubRepository> repositories = new ArrayList<>();

            for (int page = 1; page <= MAX_REPOSITORY_PAGES; page++) {
                InstallationRepositoriesResponse response = restClient.get()
                        .uri(GITHUB_API_BASE_URL + "/installation/repositories"
                                + "?per_page=" + REPOSITORY_PAGE_SIZE
                                + "&page=" + page)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .retrieve()
                        .body(InstallationRepositoriesResponse.class);

                List<RepositoryResponse> pageRepositories = response == null || response.repositories() == null
                        ? List.of()
                        : response.repositories();

                if (pageRepositories.isEmpty()) {
                    break;
                }

                for (RepositoryResponse repository : pageRepositories) {
                    repositories.add(new GithubRepository(
                            repository.fullName(),
                            repository.name(),
                            repository.owner() == null ? "" : repository.owner().login(),
                            repository.description(),
                            repository.privateRepository(),
                            repository.defaultBranch(),
                            repository.updatedAt()
                    ));
                }

                if (pageRepositories.size() < REPOSITORY_PAGE_SIZE) {
                    break;
                }
            }
            return repositories;
        } catch (RestClientResponseException e) {
            throw githubResponseFailure("GitHub 저장소 목록 조회", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub 저장소 목록 조회", e);
        }
    }

    @Override
    public Optional<GithubRepository> getRepository(Long ownerUserId, String repositoryFullName) {
        String token = getRepositoryAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            RepositoryResponse response = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(RepositoryResponse.class);
            return Optional.ofNullable(toGithubRepository(response));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw githubResponseFailure("GitHub 저장소 조회", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub 저장소 조회", e);
        }
    }

    @Override
    public String createRepository(Long ownerUserId, String repositoryName, RepositoryVisibility visibility) {
        String token = getGithubUserAccessToken(ownerUserId);

        try {
            CreateRepositoryResponse response = restClient.post()
                    .uri(GITHUB_API_BASE_URL + "/user/repos")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(new CreateRepositoryRequest(repositoryName, visibility == RepositoryVisibility.PRIVATE, true))
                    .retrieve()
                    .body(CreateRepositoryResponse.class);

            if (response == null || response.fullName() == null || response.fullName().isBlank()) {
                throw new IllegalStateException("GitHub 저장소 생성 결과를 확인할 수 없습니다.");
            }
            return response.fullName();
        } catch (RestClientResponseException e) {
            throw githubResponseFailure("GitHub 저장소 생성", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub 저장소 생성", e);
        }
    }

    @Override
    public boolean repositoryExists(Long ownerUserId, String repositoryFullName) {
        return getRepository(ownerUserId, repositoryFullName).isPresent();
    }

    @Override
    public List<GithubCommit> getRecentCommits(Long ownerUserId, String repositoryFullName, int limit) {
        String token = getRepositoryAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            CommitResponse[] commits = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo + "/commits?per_page=" + Math.max(1, Math.min(limit, 100)))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(CommitResponse[].class);

            if (commits == null || commits.length == 0) {
                return List.of();
            }

            return List.of(commits).stream()
                    .map(commit -> new GithubCommit(
                            commit.sha(),
                            commit.commit() == null ? "" : commit.commit().message(),
                            commit.commit() == null || commit.commit().author() == null
                                    ? "unknown"
                                    : commit.commit().author().name(),
                            commit.commit() == null || commit.commit().author() == null
                                    ? OffsetDateTime.now()
                                    : commit.commit().author().date()
                    ))
                    .toList();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw githubResponseFailure("GitHub 커밋 조회", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub 커밋 조회", e);
        }
    }

    @Override
    public RepositoryHealthStatus checkRepositoryHealth(Long ownerUserId, String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            return RepositoryHealthStatus.REPOSITORY_NOT_FOUND;
        }

        String token = getRepositoryAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
            return RepositoryHealthStatus.HEALTHY;
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 404) {
                return RepositoryHealthStatus.REPOSITORY_NOT_FOUND;
            }
            if (code == 401 || code == 403) {
                return RepositoryHealthStatus.ACCESS_DENIED;
            }
            if (code == 422) {
                return RepositoryHealthStatus.PERMISSION_MISMATCH;
            }
            return RepositoryHealthStatus.UNKNOWN_ERROR;
        }
    }

    @Override
    public void deleteRepository(Long ownerUserId, String repositoryFullName) {
        String token = getRepositoryAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            restClient.delete()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw githubResponseFailure("GitHub 저장소 삭제", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub 저장소 삭제", e);
        }
    }

    @Override
    public void preparePreviewBranch(Long ownerUserId, String repositoryFullName) {
        String token = getRepositoryAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        if (isBranchExists(token, normalizedRepo, PREVIEW_BRANCH)) {
            return;
        }

        String defaultBranch = getDefaultBranch(token, normalizedRepo);
        if (defaultBranch == null) {
            return;
        }

        String defaultBranchSha = getBranchHeadSha(token, normalizedRepo, defaultBranch);
        if (defaultBranchSha == null) {
            return;
        }

        try {
            restClient.post()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo + "/git/refs")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(new CreateRefRequest("refs/heads/" + PREVIEW_BRANCH, defaultBranchSha))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 422) {
                throw githubResponseFailure("preview 브랜치 준비", e);
            }
        } catch (RestClientException e) {
            throw githubClientFailure("preview 브랜치 준비", e);
        }
    }

    private String getRepositoryAccessToken(Long ownerUserId) {
        User user = getUser(ownerUserId);
        if (user.getGithubUserAccessToken() != null && !user.getGithubUserAccessToken().isBlank()) {
            try {
                return getGithubUserAccessToken(user);
            } catch (IllegalStateException e) {
                if (!user.hasGithubAppInstalled()) {
                    throw e;
                }
            }
        }
        return getGithubInstallationAccessToken(user);
    }

    private String getGithubInstallationAccessToken(Long ownerUserId) {
        return getGithubInstallationAccessToken(getUser(ownerUserId));
    }

    private String getGithubInstallationAccessToken(User user) {
        if (!user.hasGithubAppInstalled()) {
            throw new IllegalStateException("GitHub App이 설치되지 않은 사용자입니다: " + user.getId());
        }

        try {
            return getInstallationToken(user.getGithubInstallationId());
        } catch (RestClientResponseException e) {
            throw githubResponseFailure("GitHub App installation token 발급", e);
        } catch (RestClientException e) {
            throw githubClientFailure("GitHub App installation token 발급", e);
        }
    }

    private String getGithubUserAccessToken(Long ownerUserId) {
        return getGithubUserAccessToken(getUser(ownerUserId));
    }

    private String getGithubUserAccessToken(User user) {
        if (!user.hasGithubAppInstalled()) {
            throw new IllegalStateException("GitHub App이 설치되지 않은 사용자입니다: " + user.getId());
        }

        if (user.getGithubUserAccessToken() == null || user.getGithubUserAccessToken().isBlank()) {
            throw new IllegalStateException("새 GitHub 저장소 생성에는 GitHub 사용자 액세스 토큰이 필요합니다. GitHub App을 다시 설치하거나 권한을 갱신하세요: " + user.getId());
        }

        if (!user.isUserAccessTokenExpired()) {
            return user.getGithubUserAccessToken();
        }

        String refreshToken = user.getGithubUserRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("GitHub 사용자 리프레시 토큰이 없습니다. GitHub App을 다시 설치하거나 권한을 갱신하세요: " + user.getId());
        }

        GithubAppPort.GithubUserTokenInfo tokenInfo;
        try {
            tokenInfo = githubAppPort.refreshUserToken(refreshToken);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "GitHub 사용자 토큰 갱신에 실패했습니다. 새 GitHub 저장소 생성에는 GitHub App user token이 필요하므로 "
                            + "GitHub App 권한을 다시 갱신한 뒤 재시도하세요. 원인: " + e.getMessage(),
                    e
            );
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
        user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);
        userRepository.save(user);
        return tokenInfo.accessToken();
    }

    private String getInstallationToken(Long installationId) {
        record TokenResponse(@JsonProperty("token") String token) {}
        TokenResponse response = restClient.post()
                .uri(GITHUB_API_BASE_URL + "/app/installations/" + installationId + "/access_tokens")
                .header("Authorization", "Bearer " + generateAppJwt())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalStateException("Installation Access Token 발급 실패");
        }
        return response.token();
    }

    private String generateAppJwt() {
        PrivateKey privateKey = loadPrivateKey();
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(githubProperties.app().appId())
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(540)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey loadPrivateKey() {
        try {
            String pemContent = githubProperties.app().privateKey();
            if (!pemContent.trim().startsWith("-----BEGIN")) {
                pemContent = Files.readString(Path.of(pemContent.trim()));
            }
            try (PEMParser parser = new PEMParser(new StringReader(pemContent))) {
                Object obj = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                if (obj instanceof PEMKeyPair keyPair) {
                    return converter.getKeyPair(keyPair).getPrivate();
                }
                if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo keyInfo) {
                    return converter.getPrivateKey(keyInfo);
                }
                throw new IllegalStateException("지원하지 않는 PEM 키 형식입니다");
            }
        } catch (IOException e) {
            throw new IllegalStateException("GitHub App Private Key 로드 실패", e);
        }
    }

    private User getUser(Long ownerUserId) {
        return userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다: " + ownerUserId));
    }

    private boolean isBranchExists(String token, String repositoryFullName, String branchName) {
        try {
            restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName + "/branches/" + branchName)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw githubResponseFailure("브랜치 존재 여부 조회", e);
        }
    }

    private String getDefaultBranch(String token, String repositoryFullName) {
        try {
            RepositoryResponse response = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(RepositoryResponse.class);

            if (response == null || response.defaultBranch() == null || response.defaultBranch().isBlank()) {
                return null;
            }
            return response.defaultBranch();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 409) {
                return null;
            }
            throw githubResponseFailure("기본 브랜치 조회", e);
        }
    }

    private String getBranchHeadSha(String token, String repositoryFullName, String branchName) {
        try {
            BranchResponse response = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName + "/branches/" + branchName)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(BranchResponse.class);

            if (response == null || response.commit() == null || response.commit().sha() == null || response.commit().sha().isBlank()) {
                return null;
            }
            return response.commit().sha();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 409) {
                return null;
            }
            throw githubResponseFailure("브랜치 SHA 조회", e);
        }
    }

    private String normalizeRepositoryFullName(String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            throw new IllegalArgumentException("repositoryFullName must not be blank");
        }
        String value = repositoryFullName.trim();
        if (!value.contains("/")) {
            throw new IllegalArgumentException("repositoryFullName must be in owner/repo format");
        }
        return value;
    }

    private IllegalStateException githubResponseFailure(String operation, RestClientResponseException e) {
        return new IllegalStateException(operation + " 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
    }

    private IllegalStateException githubClientFailure(String operation, RestClientException e) {
        return new IllegalStateException(operation + " 실패: " + e.getMessage(), e);
    }

    private GithubRepository toGithubRepository(RepositoryResponse repository) {
        if (repository == null) {
            return null;
        }
        return new GithubRepository(
                repository.fullName(),
                repository.name(),
                repository.owner() == null ? "" : repository.owner().login(),
                repository.description(),
                repository.privateRepository(),
                repository.defaultBranch(),
                repository.updatedAt()
        );
    }

    private record CreateRepositoryRequest(
            @JsonProperty("name") String name,
            @JsonProperty("private") boolean isPrivate,
            @JsonProperty("auto_init") boolean autoInit
    ) {
    }

    private record CreateRepositoryResponse(
            @JsonProperty("full_name") String fullName
    ) {
    }

    private record CommitResponse(
            @JsonProperty("sha") String sha,
            @JsonProperty("commit") CommitDetail commit
    ) {
    }

    private record CommitDetail(
            @JsonProperty("message") String message,
            @JsonProperty("author") CommitAuthor author
    ) {
    }

    private record CommitAuthor(
            @JsonProperty("name") String name,
            @JsonProperty("date") OffsetDateTime date
    ) {
    }

    private record InstallationRepositoriesResponse(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("repositories") List<RepositoryResponse> repositories
    ) {
    }

    private record RepositoryResponse(
            @JsonProperty("full_name") String fullName,
            @JsonProperty("name") String name,
            @JsonProperty("owner") RepositoryOwner owner,
            @JsonProperty("description") String description,
            @JsonProperty("private") boolean privateRepository,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("updated_at") OffsetDateTime updatedAt
    ) {
    }

    private record RepositoryOwner(
            @JsonProperty("login") String login
    ) {
    }

    private record BranchResponse(
            @JsonProperty("commit") BranchCommit commit
    ) {
    }

    private record BranchCommit(
            @JsonProperty("sha") String sha
    ) {
    }

    private record CreateRefRequest(
            @JsonProperty("ref") String ref,
            @JsonProperty("sha") String sha
    ) {
    }
}
