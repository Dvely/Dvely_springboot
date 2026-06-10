package com.example.dvely.webhook.application;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final GithubProperties githubProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProjectRepository projectRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ChangeService changeService;

    public void verifySignature(byte[] payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new IllegalArgumentException("유효하지 않은 webhook 서명 형식");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    githubProperties.app().webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));

            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("webhook 서명 불일치");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("서명 검증 중 오류 발생", e);
        }
    }

    public void handleEvent(String eventType, byte[] payload) {
        log.info("GitHub webhook 수신: event={}", eventType);
        switch (eventType) {
            case "workflow_run" -> handleWorkflowRun(payload);
            case "push"         -> handlePush(payload);
            case "pull_request" -> handlePullRequest(payload);
            case "installation" -> handleInstallation(payload);
            default -> log.debug("처리하지 않는 webhook 이벤트: {}", eventType);
        }
    }

    @Transactional
    private void handleWorkflowRun(byte[] payload) {
        try {
            JsonNode root       = objectMapper.readTree(payload);
            JsonNode workflowRun = root.path("workflow_run");

            String workflowName = workflowRun.path("name").asText();
            String runStatus    = workflowRun.path("status").asText();
            String conclusion   = workflowRun.path("conclusion").asText();
            Long runId          = workflowRun.path("id").isNumber()
                    ? workflowRun.path("id").asLong()
                    : null;
            String runTitle     = workflowRun.path("display_title").asText();
            String headSha      = workflowRun.path("head_sha").asText();
            String repoFullName = root.path("repository").path("full_name").asText();

            if (!DeployWorkflowTemplate.isQeployWorkflowName(workflowName)) {
                return;
            }
            if (!"completed".equals(runStatus)) {
                return;
            }

            log.info("workflow_run 완료: repo={}, conclusion={}", repoFullName, conclusion);

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
            if (history.getWorkflowRunId() == null && runId != null) {
                history.assignRunId(runId);
            }
            if (history.getStatus() == DeployStatus.LIVE
                    || history.getStatus() == DeployStatus.FAILED) {
                return;
            }

            if ("success".equals(conclusion)) {
                history.complete();
                if (history.getTaskId() != null) {
                    changeService.markDeployed(history.getTaskId());
                }
                if (isLatestProjectDeployment(history)) {
                    project.updateDeployment(DeployStatus.LIVE, history.getDeployedUrl(), history.getVersionLabel());
                    projectRepository.save(project);
                }
                log.info("배포 완료 → LIVE: projectId={}, historyId={}", project.getId(), history.getId());
            } else {
                history.fail();
                if (isLatestProjectDeployment(history)) {
                    project.updateDeployment(DeployStatus.FAILED, history.getDeployedUrl(), history.getVersionLabel());
                    projectRepository.save(project);
                }
                log.info("배포 실패 → FAILED: projectId={}, historyId={}, conclusion={}", project.getId(), history.getId(), conclusion);
            }

            deploymentHistoryRepository.save(history);

        } catch (Exception e) {
            log.error("workflow_run 이벤트 처리 중 오류", e);
        }
    }

    private void handlePush(byte[] payload) {
        // TODO: push 이벤트 처리
    }

    private void handlePullRequest(byte[] payload) {
        // TODO: pull_request 이벤트 처리
    }

    private void handleInstallation(byte[] payload) {
        // TODO: installation 이벤트 처리
    }

    private boolean isLatestProjectDeployment(DeploymentHistory history) {
        return deploymentHistoryRepository.findLatestByProjectId(history.getProjectId())
                .map(latest -> latest.getId().equals(history.getId()))
                .orElse(false);
    }
}
