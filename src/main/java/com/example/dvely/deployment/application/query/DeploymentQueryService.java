package com.example.dvely.deployment.application.query;

import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.DeploymentLogs;
import com.example.dvely.deployment.application.port.out.GithubActionsPort.WorkflowRunStatus;
import com.example.dvely.deployment.application.result.DeploymentCandidateResult;
import com.example.dvely.deployment.application.result.DeploymentHistoryResult;
import com.example.dvely.deployment.application.result.DeploymentLogsResult;
import com.example.dvely.deployment.application.result.DeploymentStatusResult;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeploymentQueryService {

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final GithubActionsPort githubActionsPort;

    @Transactional(readOnly = true)
    public DeploymentStatusResult getDeploymentStatus(Long historyId) {
        DeploymentHistory history = deploymentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new IllegalArgumentException("배포 이력을 찾을 수 없습니다. historyId=" + historyId));

        // LIVE/FAILED는 DB 상태만 반환 (GitHub Actions 조회 불필요)
        if (history.getStatus() != DeployStatus.IN_PROGRESS) {
            return toStatusResult(history, null);
        }

        // IN_PROGRESS: GitHub Actions에서 실시간 빌드 상태 조회
        Project project = projectRepository.findById(history.getProjectId())
                .orElseThrow(() -> new IllegalStateException("프로젝트를 찾을 수 없습니다. projectId=" + history.getProjectId()));

        User user = userRepository.findById(project.getOwnerUserId())
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다."));

        WorkflowRunStatus runStatus = githubActionsPort.getLatestRunStatus(
                user.getGithubUserAccessToken(),
                project.getSourceRepository(),
                DeployWorkflowTemplate.fileName(),
                history.getTriggeredAt());

        return toStatusResult(history, runStatus);
    }

    private DeploymentStatusResult toStatusResult(DeploymentHistory h, WorkflowRunStatus run) {
        return new DeploymentStatusResult(
                h.getId(),
                h.getProjectId(),
                h.getDeployTargetType().name(),
                h.getVersionLabel(),
                h.getDeployedUrl(),
                h.getStatus().name(),
                run != null ? run.status() : null,
                run != null ? run.conclusion() : null,
                h.getTriggeredAt(),
                h.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<DeploymentHistoryResult> getDeploymentHistories(Long projectId) {
        return deploymentHistoryRepository.findByProjectIdOrderByTriggeredAtDesc(projectId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    private DeploymentHistoryResult toResult(DeploymentHistory h) {
        return new DeploymentHistoryResult(
                h.getId(),
                h.getProjectId(),
                h.getDeployTargetType().name(),
                h.getVersionLabel(),
                h.getDeployedUrl(),
                h.getStatus().name(),
                h.getTriggeredAt(),
                h.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<VersionResult> getVersions(Long ownerUserId, Long projectId) {
        // TODO: merge 기준 버전 목록 조회 구현
        return List.of();
    }

    @Transactional(readOnly = true)
    public VersionDetailResult getVersionDetail(Long ownerUserId, Long versionId) {
        // TODO: 버전 상세 조회 구현
        throw new UnsupportedOperationException("버전 상세 조회 미구현");
    }

    @Transactional(readOnly = true)
    public List<DeploymentCandidateResult> getDeploymentCandidates(Long ownerUserId, Long projectId) {
        // TODO: 배포 이력 중 LIVE, PREVIEW_READY 상태인 버전만 조회
        return List.of();
    }

    @Transactional(readOnly = true)
    public DeploymentLogsResult getDeploymentLogs(Long historyId) {
        DeploymentHistory history = deploymentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new IllegalArgumentException("배포 이력을 찾을 수 없습니다. historyId=" + historyId));

        Long runId = history.getWorkflowRunId();
        if (runId == null) {
            return new DeploymentLogsResult(historyId, null, List.of(), "");
        }

        Project project = projectRepository.findById(history.getProjectId())
                .orElseThrow(() -> new IllegalStateException("프로젝트를 찾을 수 없습니다."));

        User user = userRepository.findById(project.getOwnerUserId())
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다."));

        DeploymentLogs logs = githubActionsPort.getJobLogs(
                user.getGithubUserAccessToken(),
                project.getSourceRepository(),
                runId);

        List<DeploymentLogsResult.JobResult> jobResults = logs.jobs().stream()
                .map(j -> new DeploymentLogsResult.JobResult(
                        j.jobId(),
                        j.name(),
                        j.status(),
                        j.conclusion(),
                        j.steps().stream()
                                .map(s -> new DeploymentLogsResult.StepResult(
                                        s.number(), s.name(), s.status(), s.conclusion()))
                                .toList()
                ))
                .toList();

        return new DeploymentLogsResult(historyId, runId, jobResults, logs.logText());
    }
}
