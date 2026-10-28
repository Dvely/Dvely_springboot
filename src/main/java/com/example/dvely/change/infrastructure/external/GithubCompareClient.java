package com.example.dvely.change.infrastructure.external;

import com.example.dvely.change.application.port.out.GithubComparePort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * GitHub compare·commit 조회(#532). 배포 쪽 {@code GithubRepoClient} 와 같은 공용 RestClient 빈을 쓰되,
 * 반영 범위에만 필요한 필드를 읽으려고 따로 둔다.
 */
@Component
public class GithubCompareClient implements GithubComparePort {

    private static final String API_BASE = "https://api.github.com";
    /** compare 응답이 한 페이지에 담는 커밋 수의 최대치. 파일 목록은 첫 페이지에만 온다. */
    private static final int COMMITS_PER_PAGE = 250;

    private final RestClient githubRestClient;

    public GithubCompareClient(@Qualifier("githubRestClient") RestClient githubRestClient) {
        this.githubRestClient = githubRestClient;
    }

    @Override
    public Comparison compare(String userToken, String repoFullName, String base, String head) {
        String[] parts = splitRepo(repoFullName);
        CompareResponse response;
        try {
            response = restClient(userToken).get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/compare/{base}...{head}?per_page={perPage}",
                            parts[0], parts[1], base, head, COMMITS_PER_PAGE)
                    .retrieve()
                    .body(CompareResponse.class);
        } catch (RestClientResponseException e) {
            // 브랜치가 없을 때도, 공통 조상이 없을 때도 404 다. head 가 없는 것만 "아직 올린 것 없음"으로 읽는다.
            if (e.getStatusCode().value() == 404 && !branchExists(userToken, parts, head)) {
                return Comparison.empty();
            }
            throw new IllegalStateException("브랜치 비교 실패 (status=" + e.getStatusCode().value() + ")", e);
        }
        if (response == null) {
            throw new IllegalStateException("브랜치 비교 응답이 비어 있습니다");
        }
        return toComparison(response);
    }

    @Override
    public List<String> commitFiles(String userToken, String repoFullName, String sha) {
        String[] parts = splitRepo(repoFullName);
        CommitResponse response;
        try {
            response = restClient(userToken).get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/commits/{sha}", parts[0], parts[1], sha)
                    .retrieve()
                    .body(CommitResponse.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("커밋 조회 실패 (status=" + e.getStatusCode().value() + ")", e);
        }
        List<String> paths = new ArrayList<>();
        if (response != null && response.files() != null) {
            for (FileResponse file : response.files()) {
                paths.add(file.filename());
                if (file.previousFilename() != null) {
                    paths.add(file.previousFilename());
                }
            }
        }
        return paths;
    }

    static Comparison toComparison(CompareResponse response) {
        List<Commit> commits = new ArrayList<>();
        if (response.commits() != null) {
            for (CommitResponse commit : response.commits()) {
                List<String> parents = commit.parents() == null ? List.of()
                        : commit.parents().stream().map(ParentResponse::sha).toList();
                String message = commit.commit() == null ? "" : commit.commit().message();
                commits.add(new Commit(commit.sha(), message == null ? "" : message, parents));
            }
        }
        List<File> files = new ArrayList<>();
        if (response.files() != null) {
            for (FileResponse file : response.files()) {
                // 바뀐 바이너리는 줄 수가 0 이고 patch 가 없다. 0 줄 변경으로 보이지 않게 null 로 둔다.
                // 내용 없이 이름만 바뀐 파일·빈 파일 추가는 실제로 0 줄이라 그대로 둔다.
                boolean uncounted = file.patch() == null && file.additions() == 0 && file.deletions() == 0
                        && "modified".equals(normalizeStatus(file.status()));
                files.add(new File(
                        file.filename(),
                        normalizeStatus(file.status()),
                        uncounted ? null : file.additions(),
                        uncounted ? null : file.deletions(),
                        "renamed".equals(file.status()) ? file.previousFilename() : null));
            }
        }
        return new Comparison(commits, Math.max(response.totalCommits(), commits.size()), files);
    }

    /** GitHub 는 copied·changed·unchanged 도 준다. 화면에는 네 가지로만 보인다. */
    static String normalizeStatus(String status) {
        return switch (status == null ? "" : status) {
            case "added", "copied" -> "added";
            case "removed" -> "removed";
            case "renamed" -> "renamed";
            default -> "modified";
        };
    }

    private boolean branchExists(String userToken, String[] parts, String branch) {
        try {
            restClient(userToken).get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/branches/{branch}", parts[0], parts[1], branch)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException("브랜치 조회 실패 (status=" + e.getStatusCode().value() + ")", e);
        }
    }

    /** 사용자 토큰마다 파생시킨다. 공용 빈의 기본 헤더에 토큰을 박으면 다른 사용자의 요청에 실린다. */
    private RestClient restClient(String userToken) {
        return githubRestClient.mutate()
                .defaultHeader("Authorization", "Bearer " + userToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private static String[] splitRepo(String repoFullName) {
        String[] parts = repoFullName == null ? new String[0] : repoFullName.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException("저장소 이름 형식이 올바르지 않습니다: " + repoFullName);
        }
        return parts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompareResponse(
            @JsonProperty("total_commits") int totalCommits,
            @JsonProperty("commits") List<CommitResponse> commits,
            @JsonProperty("files") List<FileResponse> files
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitResponse(
            @JsonProperty("sha") String sha,
            @JsonProperty("commit") CommitDetail commit,
            @JsonProperty("parents") List<ParentResponse> parents,
            @JsonProperty("files") List<FileResponse> files
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitDetail(@JsonProperty("message") String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParentResponse(@JsonProperty("sha") String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileResponse(
            @JsonProperty("filename") String filename,
            @JsonProperty("status") String status,
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions,
            @JsonProperty("patch") String patch,
            @JsonProperty("previous_filename") String previousFilename
    ) {}
}
