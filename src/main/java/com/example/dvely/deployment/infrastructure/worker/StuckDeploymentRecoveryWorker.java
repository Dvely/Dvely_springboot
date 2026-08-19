package com.example.dvely.deployment.infrastructure.worker;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.deployment.application.port.out.GithubActionsPort;
import com.example.dvely.deployment.application.service.DeploymentOutcomeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.deployment.infrastructure.config.StuckDeploymentRecoveryProperties;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과 웹훅을 놓쳐 IN_PROGRESS 에 멈춘 배포를 GitHub 에 직접 물어 회수한다.
 *
 * 워커는 PENDING 만 집으므로 웹훅을 한 번 놓치면 그 이력은 영구히 멈춘다. 화면에는 그것이
 * "진행 중"으로도 "실패"로도 보이지 않는다 — 대화에는 "접수했습니다"가 마지막 말로 남고 폴링은
 * 붙을 메시지가 없어 조용히 끝난다. 사용자에게는 아무 일도 일어나지 않은 것처럼 보인다.
 *
 * 그렇다고 시간이 지났다고 FAILED 로 닫아서는 안 된다. 실제로 겪은 사고가 정확히 그 반대였다 —
 * GitHub 쪽은 전부 성공하고 사이트도 200 으로 떴는데 우리 이력만 멈춰 있었다. 그것을 실패로
 * 적으면 멀쩡히 배포된 것을 실패했다고 사용자에게 알리게 된다. 그래서 이 워커는 상태를
 * 추측하지 않고 GitHub 의 실행 결과를 읽어 그대로 반영한다. 판정을 못 얻은 것만 마지막 수단으로
 * 닫는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckDeploymentRecoveryWorker {

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;
    private final GithubActionsPort githubActionsPort;
    private final DeploymentOutcomeService deploymentOutcomeService;
    private final StuckDeploymentRecoveryProperties properties;

    @Scheduled(fixedDelayString = "${qeploy.deployment.recovery.poll-interval-ms:60000}")
    public void recoverStuckDeployments() {
        LocalDateTime graceCutoff = LocalDateTime.now().minusMinutes(properties.graceMinutesOrDefault());
        for (DeploymentHistory history : deploymentHistoryRepository.findDispatchedAwaitingOutcome(
                graceCutoff, properties.batchSizeOrDefault())) {
            try {
                recover(history);
            } catch (RuntimeException exception) {
                // 한 이력의 실패가 나머지를 막지 않는다. GitHub 호출은 레이트 리밋·토큰 만료로
                // 언제든 실패할 수 있고 다음 주기에 다시 물으면 되는 성격이다.
                log.warn("멈춘 배포 회수 실패 — 다음 주기에 재시도: historyId={} 원인={}",
                        history.getId(), exception.toString());
            }
        }
    }

    private void recover(DeploymentHistory history) {
        Project project = projectRepository.findById(history.getProjectId()).orElse(null);
        if (project == null) {
            log.warn("멈춘 배포의 프로젝트가 없음: historyId={} projectId={}",
                    history.getId(), history.getProjectId());
            return;
        }

        GithubActionsPort.WorkflowRunStatus run = readRunStatus(history, project);
        if (run == null || !"completed".equals(run.status())) {
            // 아직 도는 중일 수 있다. 배포가 오래 걸리는 것은 정상이므로 여기서 닫지 않는다.
            abandonIfHopeless(history, project, run == null
                    ? "GitHub Actions 실행을 찾지 못했습니다."
                    : "GitHub Actions 실행이 " + properties.abandonMinutesOrDefault() + "분이 지나도 끝나지 않았습니다.");
            return;
        }

        if ("success".equals(run.conclusion())) {
            log.info("멈춘 배포 회수 — 성공으로 확정: historyId={} runId={}", history.getId(), run.runId());
            deploymentOutcomeService.applySuccess(history, project);
            return;
        }
        log.info("멈춘 배포 회수 — 실패로 확정: historyId={} runId={} conclusion={}",
                history.getId(), run.runId(), run.conclusion());
        deploymentOutcomeService.applyFailure(
                history, project, "GitHub Actions workflow conclusion: " + run.conclusion());
    }

    /**
     * GitHub 에서 이 배포의 실행 상태를 읽는다. 디스패치 때 실행을 못 찾아 runId 가 비어 있는
     * 이력도 있으므로(finishDispatch 는 runId 가 null 이어도 IN_PROGRESS 로 넘긴다),
     * 그때는 correlationId 로 다시 찾는다.
     */
    private GithubActionsPort.WorkflowRunStatus readRunStatus(DeploymentHistory history, Project project) {
        String userToken = resolveUserToken(history.getOwnerUserId());
        if (history.getWorkflowRunId() != null) {
            return githubActionsPort.getWorkflowRunStatus(
                    userToken, project.getSourceRepository(), history.getWorkflowRunId());
        }
        if (history.getCorrelationId() == null) {
            return null;
        }
        GithubActionsPort.WorkflowRunMatch match = githubActionsPort.findWorkflowRun(
                userToken,
                project.getSourceRepository(),
                DeployWorkflowTemplate.fileName(),
                history.getCorrelationId(),
                history.getWorkflowHeadSha(),
                history.getTriggeredAt()
        );
        if (match == null || match.runId() == null) {
            return null;
        }
        return new GithubActionsPort.WorkflowRunStatus(match.runId(), match.status(), match.conclusion());
    }

    /**
     * 판정을 얻지 못한 채 포기 시각을 넘긴 배포만 닫는다. 이 경로는 "실패했다"가 아니라
     * "결과를 확인할 수 없다"이므로 사유를 그렇게 적는다 — 사용자가 사이트를 열어보면 떠 있을
     * 수도 있고, 그때 "실패"라고 적혀 있으면 그것이 더 나쁜 거짓말이다.
     */
    private void abandonIfHopeless(DeploymentHistory history, Project project, String reason) {
        LocalDateTime abandonCutoff = LocalDateTime.now().minusMinutes(properties.abandonMinutesOrDefault());
        if (history.getUpdatedAt() == null || history.getUpdatedAt().isAfter(abandonCutoff)) {
            return;
        }
        log.warn("멈춘 배포 회수 포기 — 결과 미확인으로 닫는다: historyId={} runId={} 사유={}",
                history.getId(), history.getWorkflowRunId(), reason);
        deploymentOutcomeService.applyFailure(
                history, project, "배포 결과를 확인할 수 없습니다. " + reason);
    }

    private String resolveUserToken(Long ownerUserId) {
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new IllegalStateException("유저를 찾을 수 없습니다. userId=" + ownerUserId));
        if (user.isUserAccessTokenExpired()) {
            return authCommandService.refreshGithubUserToken(ownerUserId);
        }
        return user.getGithubUserAccessToken();
    }
}
