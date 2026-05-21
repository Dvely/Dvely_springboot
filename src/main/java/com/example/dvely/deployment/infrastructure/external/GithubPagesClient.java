package com.example.dvely.deployment.infrastructure.external;

import com.example.dvely.deployment.application.port.out.GithubPagesPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class GithubPagesClient implements GithubPagesPort {

    private static final String API_BASE = "https://api.github.com";

    @Override
    public PagesInfo getPages(String userToken, String repoFullName) {
        String[] parts = splitRepo(repoFullName);
        try {
            PagesResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .retrieve()
                    .body(PagesResponse.class);

            if (response == null) {
                return new PagesInfo(false, null, null, null);
            }
            return new PagesInfo(true, response.htmlUrl(), response.sourceBranch(), response.cname());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return new PagesInfo(false, null, null, null);
            }
            throw new IllegalStateException(githubError("GitHub Pages 정보 조회 실패", e), e);
        }
    }

    @Override
    public String enablePages(String userToken, String repoFullName, String branch) {
        String[] parts = splitRepo(repoFullName);

        // gh-pages 브랜치가 없으면 default 브랜치 HEAD에서 자동 생성
        ensureBranchExists(userToken, parts[0], parts[1], branch);

        try {
            PagesResponse response = restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .body(Map.of("source", Map.of("branch", branch, "path", "/")))
                    .retrieve()
                    .body(PagesResponse.class);

            if (response == null || response.htmlUrl() == null) {
                return buildDefaultPagesUrl(parts[0], parts[1]);
            }
            return response.htmlUrl();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages 활성화 실패", e), e);
        }
    }

    @Override
    public String updatePagesSource(String userToken, String repoFullName, String branch, String customDomain) {
        String[] parts = splitRepo(repoFullName);
        Map<String, Object> body = new HashMap<>();
        body.put("source", Map.of("branch", branch, "path", "/"));
        if (customDomain != null && !customDomain.isBlank()) {
            body.put("cname", customDomain);
        }

        try {
            restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pages", parts[0], parts[1])
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("GitHub Pages 소스 변경 실패", e), e);
        }

        return buildDefaultPagesUrl(parts[0], parts[1]);
    }

    @Override
    public String createBranchFromTag(String userToken, String repoFullName, String tagName, String branchName) {
        String[] parts = splitRepo(repoFullName);

        // tag SHA 조회
        TagRefResponse tagRef;
        try {
            tagRef = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs/tags/{tag}", parts[0], parts[1], tagName)
                    .retrieve()
                    .body(TagRefResponse.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("tag를 찾을 수 없습니다: " + tagName, e);
        }

        if (tagRef == null || tagRef.sha() == null) {
            throw new IllegalStateException("tag SHA 조회 실패: " + tagName);
        }

        String sha = resolveCommitSha(userToken, parts[0], parts[1], tagRef.sha(), tagRef.type());

        // 브랜치 생성 (이미 존재하면 무시)
        try {
            restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs", parts[0], parts[1])
                    .body(Map.of("ref", "refs/heads/" + branchName, "sha", sha))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422) {
                log.info("브랜치 {} 이미 존재, 기존 브랜치 사용", branchName);
            } else {
                throw new IllegalStateException(githubError("브랜치 생성 실패: " + branchName, e), e);
            }
        }

        return branchName;
    }

    // ── 브랜치 존재 보장 ────────────────────────────────────────────────────────

    /**
     * 지정 브랜치가 없으면 default 브랜치 HEAD에서 자동 생성한다.
     * LATEST 배포 시 gh-pages 브랜치가 없는 신규 저장소에서 발생하는 422를 방지한다.
     */
    private void ensureBranchExists(String userToken, String owner, String repo, String branch) {
        if (branchExists(userToken, owner, repo, branch)) {
            return;
        }

        log.info("브랜치 {} 없음 → default 브랜치에서 생성: {}/{}", branch, owner, repo);
        String defaultBranch = getDefaultBranch(userToken, owner, repo);
        String sha = getHeadSha(userToken, owner, repo, defaultBranch);

        try {
            restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs", owner, repo)
                    .body(Map.of("ref", "refs/heads/" + branch, "sha", sha))
                    .retrieve()
                    .toBodilessEntity();
            log.info("브랜치 {} 생성 완료", branch);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 422) {
                throw new IllegalStateException(githubError("브랜치 자동 생성 실패: " + branch, e), e);
            }
        }
    }

    private boolean branchExists(String userToken, String owner, String repo, String branch) {
        try {
            restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException(githubError("브랜치 조회 실패: " + branch, e), e);
        }
    }

    private String getDefaultBranch(String userToken, String owner, String repo) {
        try {
            RepoInfoResponse info = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(RepoInfoResponse.class);
            if (info == null || info.defaultBranch() == null) {
                return "main";
            }
            return info.defaultBranch();
        } catch (RestClientResponseException e) {
            log.warn("default 브랜치 조회 실패, main 사용: {}", e.getMessage());
            return "main";
        }
    }

    private String getHeadSha(String userToken, String owner, String repo, String branch) {
        try {
            BranchResponse br = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
                    .retrieve()
                    .body(BranchResponse.class);
            if (br == null || br.commitSha() == null) {
                throw new IllegalStateException("브랜치 HEAD SHA 조회 실패: " + branch);
            }
            return br.commitSha();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("브랜치 HEAD 조회 실패: " + branch, e), e);
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────────

    private String resolveCommitSha(String userToken, String owner, String repo, String sha, String type) {
        if (!"tag".equals(type)) {
            return sha;
        }
        try {
            TagObjectResponse tagObj = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/tags/{sha}", owner, repo, sha)
                    .retrieve()
                    .body(TagObjectResponse.class);
            return (tagObj != null && tagObj.commitSha() != null) ? tagObj.commitSha() : sha;
        } catch (RestClientResponseException e) {
            log.warn("annotated tag SHA 조회 실패, lightweight SHA 사용: {}", sha);
            return sha;
        }
    }

    private String buildDefaultPagesUrl(String owner, String repo) {
        return "https://" + owner + ".github.io/" + repo + "/";
    }

    private String[] splitRepo(String repoFullName) {
        String[] parts = repoFullName.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repoFullName);
        }
        return parts;
    }

    /** GitHub API 에러 응답 본문을 메시지에 포함해 원인 파악을 쉽게 한다. */
    private String githubError(String prefix, RestClientResponseException e) {
        return prefix + " (status=" + e.getStatusCode().value() + ", body=" + e.getResponseBodyAsString() + ")";
    }

    private RestClient restClient(String userToken) {
        return RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + userToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    // ── Response DTOs ────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PagesResponse(
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("status") String status,
            @JsonProperty("source") SourceDto source,
            @JsonProperty("cname") String cname
    ) {
        String sourceBranch() { return source != null ? source.branch() : null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SourceDto(
            @JsonProperty("branch") String branch,
            @JsonProperty("path") String path
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RepoInfoResponse(
            @JsonProperty("default_branch") String defaultBranch
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BranchResponse(
            @JsonProperty("commit") CommitDto commit
    ) {
        String commitSha() { return commit != null ? commit.sha() : null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitDto(
            @JsonProperty("sha") String sha
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagRefResponse(
            @JsonProperty("object") RefObject object
    ) {
        String sha() { return object != null ? object.sha() : null; }
        String type() { return object != null ? object.type() : null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RefObject(
            @JsonProperty("sha") String sha,
            @JsonProperty("type") String type
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagObjectResponse(
            @JsonProperty("object") CommitRef object
    ) {
        String commitSha() { return object != null ? object.sha() : null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitRef(
            @JsonProperty("sha") String sha
    ) {}
}
