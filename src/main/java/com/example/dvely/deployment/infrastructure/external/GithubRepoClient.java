package com.example.dvely.deployment.infrastructure.external;

import com.example.dvely.deployment.application.port.out.GithubRepoPort;
import com.example.dvely.deployment.domain.value.PackageManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GithubRepoClient implements GithubRepoPort {

    private static final String API_BASE = "https://api.github.com";
    private static final Pattern SEQUENTIAL_TAG = Pattern.compile("^v(\\d+)$");

    @Override
    public boolean hasNewCommits(String userToken, String repoFullName, String base, String head) {
        String[] parts = splitRepo(repoFullName);
        try {
            CompareResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/compare/{base}...{head}",
                            parts[0], parts[1], base, head)
                    .retrieve()
                    .body(CompareResponse.class);
            return response != null && response.aheadBy() > 0;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.warn("[GithubRepoClient] 브랜치 비교 404 (head 브랜치 없음) → false 반환: repo={} base={} head={}", repoFullName, base, head);
                return false;
            }
            throw new IllegalStateException(githubError("브랜치 비교 실패", e), e);
        }
    }

    @Override
    public int createOrGetPullRequest(String userToken, String repoFullName,
                                      String head, String base, String title) {
        String[] parts = splitRepo(repoFullName);

        // 이미 오픈된 PR 확인
        Integer existingPrNumber = findOpenPullRequest(userToken, parts[0], parts[1], head, base);
        if (existingPrNumber != null) {
            log.info("기존 PR 재사용: repo={}, pr=#{}", repoFullName, existingPrNumber);
            return existingPrNumber;
        }

        try {
            PullRequestResponse response = restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pulls", parts[0], parts[1])
                    .body(Map.of("title", title, "head", head, "base", base))
                    .retrieve()
                    .body(PullRequestResponse.class);

            if (response == null) {
                throw new IllegalStateException("PR 생성 응답이 null입니다");
            }
            log.info("PR 생성 완료: repo={}, pr=#{}", repoFullName, response.number());
            return response.number();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("PR 생성 실패", e), e);
        }
    }

    @Override
    public String mergePullRequest(String userToken, String repoFullName, int prNumber) {
        String[] parts = splitRepo(repoFullName);
        try {
            MergeResponse response = restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pulls/{pull_number}/merge",
                            parts[0], parts[1], prNumber)
                    .body(Map.of("merge_method", "merge"))
                    .retrieve()
                    .body(MergeResponse.class);

            if (response == null || response.sha() == null) {
                throw new IllegalStateException("PR merge 응답이 null입니다");
            }
            log.info("PR merge 완료: repo={}, pr=#{}, sha={}", repoFullName, prNumber, response.sha());
            return response.sha();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("PR merge 실패", e), e);
        }
    }

    @Override
    public String getHeadCommitSha(String userToken, String repoFullName, String branch) {
        String[] parts = splitRepo(repoFullName);
        try {
            BranchResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/branches/{branch}",
                            parts[0], parts[1], branch)
                    .retrieve()
                    .body(BranchResponse.class);

            if (response == null || response.commitSha() == null) {
                throw new IllegalStateException("브랜치 HEAD SHA 조회 실패: " + branch);
            }
            return response.commitSha();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("브랜치 HEAD 조회 실패", e), e);
        }
    }

    @Override
    public boolean isCommitTagged(String userToken, String repoFullName, String commitSha) {
        String[] parts = splitRepo(repoFullName);
        try {
            List<TagRefResponse> tags = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs/tags", parts[0], parts[1])
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<TagRefResponse>>() {});

            if (tags == null) return false;
            return tags.stream().anyMatch(t -> commitSha.equals(t.objectSha()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return false;
            throw new IllegalStateException(githubError("태그 목록 조회 실패", e), e);
        }
    }

    @Override
    public String createNextSequentialTag(String userToken, String repoFullName, String commitSha) {
        String[] parts = splitRepo(repoFullName);

        int nextNumber = getNextSequentialNumber(userToken, parts[0], parts[1]);
        String tagName = "v" + nextNumber;

        try {
            restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs", parts[0], parts[1])
                    .body(Map.of("ref", "refs/tags/" + tagName, "sha", commitSha))
                    .retrieve()
                    .toBodilessEntity();
            log.info("태그 생성 완료: repo={}, tag={}", repoFullName, tagName);
            return tagName;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("태그 생성 실패: " + tagName, e), e);
        }
    }

    @Override
    public PackageManager detectPackageManager(String userToken, String repoFullName) {
        String[] parts = splitRepo(repoFullName);
        Set<String> rootFiles = listRootFileNames(userToken, parts[0], parts[1]);

        if (rootFiles.contains("bun.lockb") || rootFiles.contains("bun.lock")) return PackageManager.BUN;
        if (rootFiles.contains("pnpm-lock.yaml"))                               return PackageManager.PNPM;
        if (rootFiles.contains("yarn.lock"))                                    return PackageManager.YARN;
        return PackageManager.NPM;
    }

    @Override
    public String detectFrameworkType(String userToken, String repoFullName) {
        String[] parts = splitRepo(repoFullName);
        Set<String> rootFiles = listRootFileNames(userToken, parts[0], parts[1]);
        String packageJson = Optional.ofNullable(
                fetchFileContent(userToken, parts[0], parts[1], "package.json")
        ).orElse("");

        // CRA: react-scripts 가 가장 명확한 지표
        if (packageJson.contains("\"react-scripts\"")) return "cra";

        // config 파일 기반 감지 (의존성보다 우선)
        if (hasAny(rootFiles, "next.config.js", "next.config.mjs", "next.config.ts")) return "nextjs";
        if (hasAny(rootFiles, "gatsby-config.js", "gatsby-config.ts", "gatsby-config.mjs")) return "gatsby";
        if (hasAny(rootFiles, "svelte.config.js", "svelte.config.ts")) return "sveltekit";
        if (hasAny(rootFiles, "astro.config.mjs", "astro.config.js", "astro.config.ts")) return "astro";
        if (hasAny(rootFiles, "nuxt.config.js", "nuxt.config.ts")) return "nuxt";
        if (hasAny(rootFiles, "vue.config.js", "vue.config.ts")) return "vue-cli";
        if (hasAny(rootFiles, "vite.config.js", "vite.config.ts") && packageJson.contains("\"vue\"")) return "vue";

        // package.json 의존성 기반 감지 (fallback)
        if (packageJson.contains("\"next\""))             return "nextjs";
        if (packageJson.contains("\"gatsby\""))           return "gatsby";
        if (packageJson.contains("\"@sveltejs/kit\""))    return "sveltekit";
        if (packageJson.contains("\"astro\""))            return "astro";
        if (packageJson.contains("\"nuxt\""))             return "nuxt";
        if (packageJson.contains("\"@vue/cli-service\"")) return "vue-cli";
        if (packageJson.contains("\"vue\""))              return "vue";

        log.info("프레임워크 감지 불가, 저장된 templateType 사용: repo={}", repoFullName);
        return null;
    }

    private static boolean hasAny(Set<String> files, String... names) {
        for (String name : names) {
            if (files.contains(name)) return true;
        }
        return false;
    }

    @Override
    public String detectNodeVersion(String userToken, String repoFullName) {
        String[] parts = splitRepo(repoFullName);
        for (String file : List.of(".nvmrc", ".node-version")) {
            String content = fetchFileContent(userToken, parts[0], parts[1], file);
            if (content != null) {
                String version = extractMajorVersion(content.trim());
                if (version != null) {
                    log.info("Node.js 버전 감지: repo={}, file={}, version={}", repoFullName, file, version);
                    return version;
                }
            }
        }
        log.info("Node.js 버전 파일 없음, 기본값(20) 사용: repo={}", repoFullName);
        return "20";
    }

    private String fetchFileContent(String userToken, String owner, String repo, String path) {
        try {
            FileContentResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .retrieve()
                    .body(FileContentResponse.class);
            if (response == null || response.content() == null) return null;
            // GitHub returns base64-encoded content with embedded newlines
            return new String(Base64.getDecoder().decode(response.content().replaceAll("\\s", "")));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            log.warn("파일 조회 실패: {}/{}/{}: {}", owner, repo, path, e.getMessage());
            return null;
        }
    }

    private static String extractMajorVersion(String raw) {
        Matcher m = Pattern.compile("(\\d+)").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    private Set<String> listRootFileNames(String userToken, String owner, String repo) {
        try {
            List<ContentEntry> entries = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/contents/", owner, repo)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<ContentEntry>>() {});
            if (entries == null) return Set.of();
            return entries.stream().map(ContentEntry::name).collect(Collectors.toSet());
        } catch (RestClientResponseException e) {
            log.warn("루트 파일 목록 조회 실패, npm 기본값 사용: repo={}/{}, status={}", owner, repo, e.getStatusCode().value());
            return Set.of();
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private Integer findOpenPullRequest(String userToken, String owner, String repo,
                                        String head, String base) {
        try {
            List<PullRequestResponse> prs = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pulls?state=open&head={owner}:{head}&base={base}",
                            owner, repo, owner, head, base)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<PullRequestResponse>>() {});

            if (prs == null || prs.isEmpty()) return null;
            return prs.get(0).number();
        } catch (RestClientResponseException e) {
            log.warn("오픈 PR 조회 실패, 새 PR 생성 시도: {}", e.getMessage());
            return null;
        }
    }

    private int getNextSequentialNumber(String userToken, String owner, String repo) {
        try {
            List<TagRefResponse> tags = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/git/refs/tags", owner, repo)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<TagRefResponse>>() {});

            if (tags == null || tags.isEmpty()) return 1;

            return tags.stream()
                    .map(t -> {
                        String name = t.ref().replace("refs/tags/", "");
                        Matcher m = SEQUENTIAL_TAG.matcher(name);
                        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
                    })
                    .max(Comparator.naturalOrder())
                    .orElse(0) + 1;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return 1;
            throw new IllegalStateException(githubError("태그 목록 조회 실패", e), e);
        }
    }

    private String[] splitRepo(String repoFullName) {
        String[] parts = repoFullName.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("올바르지 않은 저장소 형식입니다: " + repoFullName);
        }
        return parts;
    }

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

    // ── Response DTOs ────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileContentResponse(
            @JsonProperty("content") String content
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentEntry(
            @JsonProperty("name") String name
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompareResponse(
            @JsonProperty("ahead_by") int aheadBy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PullRequestResponse(
            @JsonProperty("number") int number
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MergeResponse(
            @JsonProperty("sha") String sha
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
            @JsonProperty("ref") String ref,
            @JsonProperty("object") RefObject object
    ) {
        String objectSha() { return object != null ? object.sha() : null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RefObject(
            @JsonProperty("sha") String sha
    ) {}
}
