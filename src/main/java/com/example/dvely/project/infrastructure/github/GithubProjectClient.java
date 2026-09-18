package com.example.dvely.project.infrastructure.github;

import com.example.dvely.common.exception.GithubRepositoryAccessDeniedException;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.project.application.port.out.GithubRepositoryPort;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GithubProjectClient implements GithubRepositoryPort {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String PREVIEW_BRANCH = "preview";
    private static final int REPOSITORY_PAGE_SIZE = 100;
    private static final int MAX_REPOSITORY_PAGES = 10;

    private final UserRepository userRepository;
    private final GithubAppPort githubAppPort;
    // 유저 토큰 갱신의 유일한 경로. 여기서 직접 갱신하면 한 요청에 갱신이 둘이 되어
    // bad_refresh_token 이 난다(getGithubUserAccessToken 참고).
    private final AuthCommandService authCommandService;
    private final GithubProperties githubProperties;
    private final RestClient restClient;

    /** 목록 한 번이 GitHub 왕복 최대 10 번이다 — 사용자별로 아주 짧게만 들고 있는다. */
    private final RepositoryListCache repositoryLists = new RepositoryListCache();

    // 생성자를 직접 쓰는 이유: RestClient 빈이 여럿이라 타입만으로는 고를 수 없는데,
    // Lombok 은 필드의 @Qualifier 를 생성자 파라미터로 옮겨주지 않는다.
    public GithubProjectClient(UserRepository userRepository,
                               GithubAppPort githubAppPort,
                               AuthCommandService authCommandService,
                               GithubProperties githubProperties,
                               @Qualifier("githubRestClient") RestClient restClient) {
        this.userRepository = userRepository;
        this.githubAppPort = githubAppPort;
        this.authCommandService = authCommandService;
        this.githubProperties = githubProperties;
        this.restClient = restClient;
    }

    @Override
    public List<GithubRepository> listRepositories(Long ownerUserId, boolean refresh) {
        Instant now = Instant.now();
        if (refresh) {
            repositoryLists.invalidate(ownerUserId);
        } else {
            Optional<List<GithubRepository>> cached = repositoryLists.find(ownerUserId, now);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
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
            repositoryLists.put(ownerUserId, repositories, now);
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
            // 발급을 GithubAppClient 하나에만 둔다. 여기 있던 두 번째 구현은 PEM 을 다시 파싱하고
            // 토큰도 따로 받아서, 캐시를 어느 쪽에 달아도 다른 쪽이 그대로 새로 발급했다.
            return githubAppPort.getInstallationToken(user.getGithubInstallationId());
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

        // 갱신은 AuthCommandService 하나만 한다. 여기서 githubAppPort.refreshUserToken 을 직접
        // 부르면 한 요청 안에 갱신 경로가 둘이 되는데, GitHub 은 리프레시 토큰을 매번 회전시키므로
        // 뒤에 도는 쪽이 이미 무효가 된 값을 들고 가 bad_refresh_token 을 맞는다.
        //
        // 실제로 그렇게 깨졌다(2026-08-18 운영). RepositoryBindingService 가 먼저 갱신해 커밋까지
        // 마쳤는데, 그 커밋은 REQUIRES_NEW 라 바깥 트랜잭션의 영속성 컨텍스트에는 옛 UserEntity 가
        // 남는다. 그래서 여기 넘어온 user 는 여전히 "만료됨"으로 보였고, 낡은 리프레시 토큰으로
        // 두 번째 갱신을 시도해 실패했다 — 저장소 생성이 그 직전에서 멈췄다.
        //
        // AuthCommandService 는 갱신된 액세스 토큰을 직접 돌려준다. 다시 읽지 않는 것이 요점이다.
        try {
            return authCommandService.refreshGithubUserToken(user.getId());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "GitHub 사용자 토큰 갱신에 실패했습니다. 새 GitHub 저장소 생성에는 GitHub App user token이 필요하므로 "
                            + "GitHub App 권한을 다시 갱신한 뒤 재시도하세요. 원인: " + e.getMessage(),
                    e
            );
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

    private RuntimeException githubResponseFailure(String operation, RestClientResponseException e) {
        // 403 은 GitHub App(설치 토큰)이 그 저장소에 접근할 수 없다는 뜻이다("Resource not accessible
        // by integration"). 일반 실패로 뭉뚱그리면 FE 가 "App 설정에서 저장소 접근 허용"이라는 조치를
        // 안내할 수 없으므로 전용 예외로 갈라 errorCode 를 내려준다.
        if (e.getStatusCode().value() == 403) {
            return new GithubRepositoryAccessDeniedException(
                    operation + " 실패: GitHub App 이 저장소에 접근할 권한이 없습니다. "
                            + "GitHub App 설정에서 이 저장소 접근을 허용해주세요. (" + e.getResponseBodyAsString() + ")");
        }
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
