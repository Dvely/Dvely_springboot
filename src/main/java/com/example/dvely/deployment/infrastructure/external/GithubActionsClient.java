package com.example.dvely.deployment.infrastructure.external;

import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class GithubActionsClient implements GithubActionsPort {

    private static final String API_BASE = "https://api.github.com";

    @Override
    public boolean workflowExists(String userToken, String repoFullName, String workflowFileName) {
        String[] parts = splitRepo(repoFullName);
        try {
            restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/contents/.github/workflows/{file}",
                            parts[0], parts[1], workflowFileName)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException(githubError("워크플로우 존재 확인 실패", e), e);
        }
    }

    @Override
    public void createOrUpdateWorkflow(String userToken, String repoFullName, String workflowFileName, String content) {
        String[] parts = splitRepo(repoFullName);

        FileContentResponse existing = getFileContent(userToken, parts[0], parts[1], workflowFileName);

        if (existing != null) {
            // GitHub API는 base64 content에 줄바꿈을 포함하므로 MimeDecoder로 디코딩
            String existingContent = new String(Base64.getMimeDecoder().decode(existing.content()));
            if (existingContent.equals(content)) {
                log.info("워크플로우 파일 내용 동일, 업데이트 생략: repo={}", repoFullName);
                return;
            }
        }

        String encoded = Base64.getEncoder().encodeToString(content.getBytes());
        Map<String, Object> body = existing != null
                ? Map.of("message", "chore: update dvely deploy workflow",
                         "content", encoded,
                         "sha", existing.sha())
                : Map.of("message", "chore: add dvely deploy workflow",
                         "content", encoded);

        try {
            restClient(userToken)
                    .put()
                    .uri(API_BASE + "/repos/{owner}/{repo}/contents/.github/workflows/{file}",
                            parts[0], parts[1], workflowFileName)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("워크플로우 파일 {}", existing != null ? "갱신" : "생성");
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("워크플로우 파일 생성/갱신 실패", e), e);
        }
    }

    @Override
    public void triggerWorkflow(String userToken, String repoFullName, String workflowFileName, String ref) {
        String[] parts = splitRepo(repoFullName);
        try {
            restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/actions/workflows/{file}/dispatches",
                            parts[0], parts[1], workflowFileName)
                    .body(Map.of("ref", ref))
                    .retrieve()
                    .toBodilessEntity();
            log.info("워크플로우 dispatch 트리거: repo={}, workflow={}, ref={}", repoFullName, workflowFileName, ref);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("워크플로우 트리거 실패", e), e);
        }
    }

    private FileContentResponse getFileContent(String userToken, String owner, String repo, String workflowFileName) {
        try {
            return restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/contents/.github/workflows/{file}",
                            owner, repo, workflowFileName)
                    .retrieve()
                    .body(FileContentResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new IllegalStateException(githubError("파일 내용 조회 실패", e), e);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileContentResponse(
            @JsonProperty("sha") String sha,
            @JsonProperty("content") String content
    ) {}
}
