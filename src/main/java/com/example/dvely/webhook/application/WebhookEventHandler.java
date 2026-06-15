package com.example.dvely.webhook.application;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventHandler {

    private static final Pattern VERSION_TAG = Pattern.compile("^refs/tags/(v\\d+)$");

    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ChangeService changeService;

    @Transactional
    public boolean handle(String eventType, byte[] payload, LocalDateTime receivedAt) {
        JsonNode root = readPayload(payload);
        return switch (eventType) {
            case "workflow_run" -> {
                handleWorkflowRun(root);
                yield true;
            }
            case "push" -> {
                handlePush(root, receivedAt);
                yield true;
            }
            case "pull_request" -> {
                handlePullRequest(root, receivedAt);
                yield true;
            }
            case "installation" -> {
                handleInstallation(root);
                yield true;
            }
            default -> false;
        };
    }

    private void handleWorkflowRun(JsonNode root) {
        JsonNode workflowRun = root.path("workflow_run");

        String workflowName = workflowRun.path("name").asText();
        String runStatus = workflowRun.path("status").asText();
        String conclusion = workflowRun.path("conclusion").asText();
        Long runId = workflowRun.path("id").isNumber() ? workflowRun.path("id").asLong() : null;
        String runTitle = workflowRun.path("display_title").asText();
        String headSha = workflowRun.path("head_sha").asText();
        String repoFullName = requiredText(root.path("repository").path("full_name"), "repository.full_name");

        if (!DeployWorkflowTemplate.isQeployWorkflowName(workflowName) || !"completed".equals(runStatus)) {
            return;
        }

        DeploymentHistory history = runId == null
                ? null
                : deploymentHistoryRepository.findByWorkflowRunId(runId).orElse(null);
        if (history == null) {
            String correlationId = DeployWorkflowTemplate.correlationIdFromRunTitle(runTitle);
            history = correlationId == null
                    ? null
                    : deploymentHistoryRepository.findByCorrelationId(correlationId).orElse(null);
        }
        if (history == null) {
            log.warn("workflow_run과 일치하는 배포 이력 없음: repo={} runId={} title={}",
                    repoFullName, runId, runTitle);
            return;
        }

        Project project = projectRepository.findById(history.getProjectId()).orElse(null);
        if (project == null || !repoFullName.equalsIgnoreCase(project.getSourceRepository())) {
            log.warn("workflow_run 저장소가 배포 이력의 프로젝트와 다름: historyId={} repo={}",
                    history.getId(), repoFullName);
            return;
        }
        if (history.getWorkflowHeadSha() != null
                && !history.getWorkflowHeadSha().isBlank()
                && !history.getWorkflowHeadSha().equals(headSha)) {
            log.warn("workflow_run head SHA 불일치: historyId={} expected={} actual={}",
                    history.getId(), history.getWorkflowHeadSha(), headSha);
            return;
        }
        boolean runIdAssigned = history.getWorkflowRunId() == null && runId != null;
        if (runIdAssigned) {
            history.assignRunId(runId);
        }
        if (history.getStatus() == DeployStatus.LIVE || history.getStatus() == DeployStatus.FAILED) {
            if (runIdAssigned) {
                deploymentHistoryRepository.save(history);
            }
            return;
        }

        if ("success".equals(conclusion)) {
            history.complete();
            if (history.getTaskId() != null) {
                changeService.markDeployed(history.getTaskId());
            }
            if (isLatestProjectDeployment(history)) {
                project.updateDeployment(
                        DeployStatus.LIVE,
                        history.getDeployedUrl(),
                        history.getVersionLabel()
                );
                projectRepository.save(project);
            }
        } else {
            history.fail("GitHub Actions workflow conclusion: " + conclusion);
            if (isLatestProjectDeployment(history)) {
                project.updateDeployment(
                        DeployStatus.FAILED,
                        history.getDeployedUrl(),
                        history.getVersionLabel()
                );
                projectRepository.save(project);
            }
        }
        deploymentHistoryRepository.save(history);
    }

    private void handlePush(JsonNode root, LocalDateTime receivedAt) {
        String repoFullName = requiredText(root.path("repository").path("full_name"), "repository.full_name");
        String ref = requiredText(root.path("ref"), "ref");
        if (root.path("deleted").asBoolean(false)) {
            return;
        }

        List<Project> projects = projectRepository.findAllBySourceRepository(repoFullName);
        Matcher tagMatcher = VERSION_TAG.matcher(ref);
        if (tagMatcher.matches()) {
            projects.forEach(project -> {
                project.synchronizeRepositoryVersion(tagMatcher.group(1), receivedAt);
                projectRepository.save(project);
            });
            return;
        }

        String defaultBranch = requiredText(
                root.path("repository").path("default_branch"),
                "repository.default_branch"
        );
        if (!ref.equals("refs/heads/" + defaultBranch)) {
            return;
        }

        JsonNode headCommit = root.path("head_commit");
        String sha = requiredText(root.path("after"), "after");
        String message = nullableText(headCommit.path("message"));
        String author = firstText(
                headCommit.path("author").path("username"),
                headCommit.path("author").path("name"),
                root.path("pusher").path("name")
        );
        LocalDateTime committedAt = parseDateTime(nullableText(headCommit.path("timestamp")));

        projects.forEach(project -> {
            project.synchronizeRepositoryHead(
                    sha,
                    message,
                    author,
                    committedAt,
                    receivedAt
            );
            projectRepository.save(project);
        });
    }

    private void handlePullRequest(JsonNode root, LocalDateTime receivedAt) {
        JsonNode pullRequest = root.path("pull_request");
        if (!"closed".equals(root.path("action").asText())
                || !pullRequest.path("merged").asBoolean(false)) {
            return;
        }

        String repoFullName = requiredText(root.path("repository").path("full_name"), "repository.full_name");
        String defaultBranch = requiredText(
                root.path("repository").path("default_branch"),
                "repository.default_branch"
        );
        if (!defaultBranch.equals(pullRequest.path("base").path("ref").asText())) {
            return;
        }

        String mergeCommitSha = requiredText(
                pullRequest.path("merge_commit_sha"),
                "pull_request.merge_commit_sha"
        );
        String title = nullableText(pullRequest.path("title"));
        String mergedBy = firstText(
                pullRequest.path("merged_by").path("login"),
                pullRequest.path("user").path("login")
        );
        LocalDateTime mergedAt = parseDateTime(nullableText(pullRequest.path("merged_at")));

        projectRepository.findAllBySourceRepository(repoFullName).forEach(project -> {
            project.synchronizeRepositoryHead(
                    mergeCommitSha,
                    title,
                    mergedBy,
                    mergedAt,
                    receivedAt
            );
            projectRepository.save(project);
        });
    }

    private void handleInstallation(JsonNode root) {
        String action = requiredText(root.path("action"), "action");
        JsonNode installation = root.path("installation");
        Long installationId = installation.path("id").isNumber()
                ? installation.path("id").asLong()
                : null;
        String accountGithubId = nullableText(installation.path("account").path("id"));

        Optional<User> userOptional = installationId == null
                ? Optional.empty()
                : userRepository.findByGithubInstallationId(installationId);
        if (userOptional.isEmpty() && accountGithubId != null) {
            userOptional = userRepository.findByGithubId(new GithubId(accountGithubId));
        }
        if (userOptional.isEmpty()) {
            log.info("installation 이벤트와 연결된 사용자 없음: action={} installationId={}",
                    action, installationId);
            return;
        }

        User user = userOptional.get();
        switch (action) {
            case "deleted" -> {
                user.disconnectGithubApp();
                updateProjectHealth(user.getId(), RepositoryHealthStatus.ACCESS_DENIED);
            }
            case "suspend" -> {
                user.clearGithubAppToken();
                updateProjectHealth(user.getId(), RepositoryHealthStatus.ACCESS_DENIED);
            }
            case "created", "unsuspend", "new_permissions_accepted", "updated" -> {
                if (installationId != null) {
                    user.updateInstallationId(installationId);
                }
                updateProjectHealth(user.getId(), RepositoryHealthStatus.UNKNOWN_ERROR);
            }
            default -> {
                log.debug("처리하지 않는 installation action: {}", action);
                return;
            }
        }
        userRepository.save(user);
    }

    private void updateProjectHealth(Long ownerUserId, RepositoryHealthStatus status) {
        projectRepository.findAllByOwnerUserIdAndDeletedFalseOrderByUpdatedAtDesc(ownerUserId)
                .forEach(project -> {
                    if (project.hasSourceRepository()) {
                        project.updateRepositoryHealth(status);
                        projectRepository.save(project);
                    }
                });
    }

    private boolean isLatestProjectDeployment(DeploymentHistory history) {
        return deploymentHistoryRepository.findLatestByProjectId(history.getProjectId())
                .map(latest -> latest.getId().equals(history.getId()))
                .orElse(false);
    }

    private JsonNode readPayload(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException exception) {
            throw new IllegalArgumentException("webhook payload JSON을 읽을 수 없습니다.", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node);
        if (value == null) {
            throw new IllegalArgumentException("webhook payload 필드가 없습니다: " + field);
        }
        return value;
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = nullableText(node);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String value) {
        return value == null ? null : OffsetDateTime.parse(value).toLocalDateTime();
    }
}
