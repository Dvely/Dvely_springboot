package com.example.dvely.deployment.infrastructure.external;

import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    public void triggerWorkflow(String userToken, String repoFullName, String workflowFileName,
                                String dispatchRef, String checkoutRef) {
        String[] parts = splitRepo(repoFullName);
        try {
            restClient(userToken)
                    .post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/actions/workflows/{file}/dispatches",
                            parts[0], parts[1], workflowFileName)
                    .body(workflowDispatchBody(dispatchRef, checkoutRef))
                    .retrieve()
                    .toBodilessEntity();
            log.info("워크플로우 dispatch 트리거: repo={}, workflow={}, dispatchRef={}, checkoutRef={}",
                    repoFullName, workflowFileName, dispatchRef, checkoutRef);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("워크플로우 트리거 실패", e), e);
        }
    }

    private Map<String, Object> workflowDispatchBody(String dispatchRef, String checkoutRef) {
        String normalizedCheckoutRef = normalizeOptionalRef(checkoutRef);
        if (normalizedCheckoutRef == null || normalizedCheckoutRef.equals(dispatchRef)) {
            return Map.of("ref", dispatchRef);
        }
        return Map.of(
                "ref", dispatchRef,
                "inputs", Map.of("checkout_ref", normalizedCheckoutRef)
        );
    }

    private String normalizeOptionalRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return ref.trim();
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

    @Override
    public WorkflowRunStatus getLatestRunStatus(String userToken, String repoFullName,
                                                String workflowFileName, LocalDateTime afterTime) {
        String[] parts = splitRepo(repoFullName);
        String createdFilter = afterTime.minusMinutes(1)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        try {
            WorkflowRunsResponse response = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/actions/workflows/{file}/runs?per_page=5&event=workflow_dispatch&created=>={created}",
                            parts[0], parts[1], workflowFileName, createdFilter)
                    .retrieve()
                    .body(WorkflowRunsResponse.class);

            if (response == null || response.workflowRuns() == null || response.workflowRuns().isEmpty()) {
                return new WorkflowRunStatus(null, "queued", null);
            }

            WorkflowRunDto latest = response.workflowRuns().get(0);
            return new WorkflowRunStatus(latest.id(), latest.status(), latest.conclusion());
        } catch (RestClientResponseException e) {
            log.warn("워크플로우 run 상태 조회 실패: {}", e.getMessage());
            return new WorkflowRunStatus(null, "queued", null);
        }
    }

    @Override
    public Long pollRunId(String userToken, String repoFullName, String workflowFileName,
                          LocalDateTime afterTime, int maxRetries, long retryIntervalMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            WorkflowRunStatus status = getLatestRunStatus(userToken, repoFullName, workflowFileName, afterTime);
            if (status.runId() != null) {
                log.info("워크플로우 run_id 확인: repo={}, runId={}", repoFullName, status.runId());
                return status.runId();
            }
            if (attempt < maxRetries) {
                log.debug("run_id 아직 없음, {}ms 후 재시도 ({}/{})", retryIntervalMs, attempt, maxRetries);
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        log.warn("run_id 폴링 실패: repo={}, 최대 {}회 시도", repoFullName, maxRetries);
        return null;
    }

    @Override
    public DeploymentLogs getJobLogs(String userToken, String repoFullName, Long runId) {
        String[] parts = splitRepo(repoFullName);

        JobsResponse jobsResponse;
        try {
            jobsResponse = restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/actions/runs/{runId}/jobs",
                            parts[0], parts[1], runId)
                    .retrieve()
                    .body(JobsResponse.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(githubError("Jobs 조회 실패", e), e);
        }

        if (jobsResponse == null || jobsResponse.jobs() == null || jobsResponse.jobs().isEmpty()) {
            return new DeploymentLogs(runId, List.of(), "");
        }

        List<JobInfo> jobInfos = jobsResponse.jobs().stream()
                .map(j -> new JobInfo(
                        j.id(),
                        j.name(),
                        j.status(),
                        j.conclusion(),
                        j.steps() == null ? List.of() : j.steps().stream()
                                .map(s -> new StepInfo(s.number(), s.name(), s.status(), s.conclusion()))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        Long firstJobId = jobsResponse.jobs().get(0).id();
        String logText = fetchJobLogText(userToken, parts[0], parts[1], firstJobId);

        return new DeploymentLogs(runId, jobInfos, logText);
    }

    private String fetchJobLogText(String userToken, String owner, String repo, Long jobId) {
        try {
            return restClient(userToken)
                    .get()
                    .uri(API_BASE + "/repos/{owner}/{repo}/actions/jobs/{jobId}/logs",
                            owner, repo, jobId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 410) {
                return "(로그가 만료되었습니다)";
            }
            log.warn("Job 로그 조회 실패: jobId={}, status={}", jobId, e.getStatusCode().value());
            return "";
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRunsResponse(
            @JsonProperty("workflow_runs") List<WorkflowRunDto> workflowRuns
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRunDto(
            @JsonProperty("id") Long id,
            @JsonProperty("status") String status,
            @JsonProperty("conclusion") String conclusion
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobsResponse(
            @JsonProperty("jobs") List<JobDto> jobs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobDto(
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("steps") List<StepDto> steps
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StepDto(
            @JsonProperty("number") int number,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("conclusion") String conclusion
    ) {}
}
