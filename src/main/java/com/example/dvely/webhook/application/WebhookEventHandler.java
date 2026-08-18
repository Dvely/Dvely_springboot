package com.example.dvely.webhook.application;

import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
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
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
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
    private final AuditRecorder auditRecorder;
    // 배포 결과를 대화로 돌려주기 위해 필요하다. 배포를 시작한 것이 에이전트 태스크이고 사용자가
    // 그 결과를 기다리는 곳도 그 대화이므로, 감사 로그와 DB 상태만 갱신하고 끝내면 사용자에게는
    // "접수했습니다"가 마지막 말로 남는다. deployment_histories 는 conversationId 를 갖고 있지
    // 않아 taskId 로 태스크를 찾아 얻는다.
    private final AgentMessageService agentMessageService;
    private final TaskStore taskStore;

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
        notifyDeploymentOutcome(history);
        // H8 (design §4): the already-terminal-status guard earlier in this method (returns early
        // when history.getStatus() is already LIVE or FAILED) already handles a re-delivered
        // webhook against an already-terminal history, so this line is naturally only reached once
        // per history — no idempotency check needed here beyond that existing guard.
        // actor SYSTEM (the direct cause is the webhook, not a user action), attributed to the
        // deployment's owner for traceability (design ADR-A8/H8 note).
        auditRecorder.record(new AuditEvent(
                history.getStatus() == DeployStatus.LIVE ? AuditAction.DEPLOYMENT_SUCCEEDED : AuditAction.DEPLOYMENT_FAILED,
                history.getStatus() == DeployStatus.LIVE ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED,
                AuditActorType.SYSTEM,
                history.getOwnerUserId(),
                history.getProjectId(),
                "DEPLOYMENT",
                String.valueOf(history.getId()),
                null,
                null,
                null,
                history.getStatus() == DeployStatus.FAILED ? history.getErrorMessage() : null
        ));
    }

    /**
     * 배포 결과를 시작한 대화에 알린다.
     *
     * <p>DeployAgentService 는 "배포 요청을 접수했습니다 — worker 가 비동기로 진행합니다"까지만
     * 말하고 끝난다. 그 뒤를 이어받는 곳이 여기인데 감사 로그와 DB 상태만 갱신하고 있었다. 그래서
     * 배포가 성공해 사이트가 실제로 떠도 사용자 화면에는 "접수했습니다"가 마지막 말로 남았다.</p>
     *
     * <p>에이전트를 거치지 않은 배포는 알릴 대화가 없다. taskId 가 없거나 태스크를 찾지 못하면
     * 조용히 건너뛴다 — 배포 자체는 이미 성공/실패로 확정됐으므로 여기서 던져 웹훅 처리를
     * 망가뜨릴 이유가 없다.</p>
     */
    private void notifyDeploymentOutcome(DeploymentHistory history) {
        if (history.getTaskId() == null) {
            return;
        }
        AgentTask task = taskStore.get(history.getTaskId());
        if (task == null || task.conversationId() == null) {
            return;
        }
        agentMessageService.appendAssistant(task.conversationId(), history.getStatus() == DeployStatus.LIVE
                ? "배포가 완료되었습니다.\n"
                        + "- 주소: " + history.getDeployedUrl() + "\n"
                        + "- 버전: " + history.getVersionLabel()
                : "배포가 실패했습니다.\n"
                        + "- 사유: " + history.getErrorMessage() + "\n"
                        + "다시 배포를 요청하면 같은 저장소로 재시도합니다.");
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
