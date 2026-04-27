package com.example.dvely.project.infrastructure.github;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class GithubProjectClient implements GithubRepositoryPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String PREVIEW_BRANCH = "preview";

    private final UserRepository userRepository;
    private final GithubAppPort githubAppPort;
    private final RestClient restClient = RestClient.create();

    @Override
    public String createRepository(Long ownerUserId, String repositoryName, RepositoryVisibility visibility) {
        String token = getValidGithubAccessToken(ownerUserId);

        try {
            CreateRepositoryResponse response = restClient.post()
                    .uri(GITHUB_API_BASE_URL + "/user/repos")
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(new CreateRepositoryRequest(repositoryName, visibility == RepositoryVisibility.PRIVATE))
                    .retrieve()
                    .body(CreateRepositoryResponse.class);

            if (response == null || response.fullName() == null || response.fullName().isBlank()) {
                throw new IllegalStateException("GitHub 저장소 생성 결과를 확인할 수 없습니다.");
            }
            return response.fullName();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("GitHub 저장소 생성 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public List<GithubCommit> getRecentCommits(Long ownerUserId, String repositoryFullName, int limit) {
        String token = getValidGithubAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            CommitResponse[] commits = restClient.get()
                .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo + "/commits?per_page=" + Math.max(1, Math.min(limit, 100)))
                    .header("Authorization", "token " + token)
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
            throw new IllegalStateException("GitHub 커밋 조회 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public RepositoryHealthStatus checkRepositoryHealth(Long ownerUserId, String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) {
            return RepositoryHealthStatus.REPOSITORY_NOT_FOUND;
        }

        String token = getValidGithubAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        try {
            restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo)
                    .header("Authorization", "token " + token)
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
    public void preparePreviewBranch(Long ownerUserId, String repositoryFullName) {
        String token = getValidGithubAccessToken(ownerUserId);
        String normalizedRepo = normalizeRepositoryFullName(repositoryFullName);

        if (isBranchExists(token, normalizedRepo, PREVIEW_BRANCH)) {
            return;
        }

        String defaultBranch = getDefaultBranch(token, normalizedRepo);
        String defaultBranchSha = getBranchHeadSha(token, normalizedRepo, defaultBranch);

        try {
            restClient.post()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + normalizedRepo + "/git/refs")
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(new CreateRefRequest("refs/heads/" + PREVIEW_BRANCH, defaultBranchSha))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // 동시 요청 등으로 이미 생성된 경우는 정상으로 처리한다.
            if (e.getStatusCode().value() != 422) {
                throw new IllegalStateException("preview 브랜치 준비 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
            }
        }
    }

    private String getValidGithubAccessToken(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다: " + ownerUserId));

        if (!user.hasGithubAppInstalled()) {
            throw new IllegalStateException("GitHub App이 설치되지 않은 사용자입니다: " + ownerUserId);
        }

        if (user.getGithubUserAccessToken() == null || user.getGithubUserAccessToken().isBlank()) {
            throw new IllegalStateException("GitHub 사용자 액세스 토큰이 없습니다: " + ownerUserId);
        }

        if (!user.isUserAccessTokenExpired()) {
            return user.getGithubUserAccessToken();
        }

        String refreshToken = user.getGithubUserRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("GitHub 사용자 리프레시 토큰이 없습니다: " + ownerUserId);
        }

        GithubAppPort.GithubUserTokenInfo tokenInfo = githubAppPort.refreshUserToken(refreshToken);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(tokenInfo.expiresInSeconds());
        user.updateUserToken(tokenInfo.accessToken(), tokenInfo.refreshToken(), expiresAt);
        userRepository.save(user);
        return tokenInfo.accessToken();
    }

    private boolean isBranchExists(String token, String repositoryFullName, String branchName) {
        try {
            restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName + "/branches/" + branchName)
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException("브랜치 존재 여부 조회 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    private String getDefaultBranch(String token, String repositoryFullName) {
        try {
            RepositoryResponse response = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName)
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(RepositoryResponse.class);

            if (response == null || response.defaultBranch() == null || response.defaultBranch().isBlank()) {
                throw new IllegalStateException("기본 브랜치를 확인할 수 없습니다: " + repositoryFullName);
            }
            return response.defaultBranch();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("기본 브랜치 조회 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    private String getBranchHeadSha(String token, String repositoryFullName, String branchName) {
        try {
            BranchResponse response = restClient.get()
                    .uri(GITHUB_API_BASE_URL + "/repos/" + repositoryFullName + "/branches/" + branchName)
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(BranchResponse.class);

            if (response == null || response.commit() == null || response.commit().sha() == null || response.commit().sha().isBlank()) {
                throw new IllegalStateException("기본 브랜치 SHA를 확인할 수 없습니다: " + repositoryFullName + "/" + branchName);
            }
            return response.commit().sha();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("브랜치 SHA 조회 실패 (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
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

    private record CreateRepositoryRequest(
            @JsonProperty("name") String name,
            @JsonProperty("private") boolean isPrivate
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

    private record RepositoryResponse(
            @JsonProperty("default_branch") String defaultBranch
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
