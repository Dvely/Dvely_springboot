package com.example.dvely.deployment.application.service;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.deployment.domain.repository.DeploymentHistoryRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배포의 최종 결과를 확정한다 — 이력 상태, 프로젝트 반영, 변경 기록, 대화 알림, 감사 로그.
 *
 * 이 확정을 부르는 곳이 둘이다. 정상 경로는 workflow_run 웹훅이고, 웹훅을 놓쳤을 때는
 * StuckDeploymentRecoveryWorker 가 GitHub 에 직접 물어 같은 확정을 내린다. 두 경로가 각자
 * 자기 버전을 갖고 있으면 한쪽만 고쳐져 배포는 성공했는데 대화에는 아무 말도 안 남는 식으로
 * 갈라진다 — 그것이 이 클래스를 따로 둔 이유다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentOutcomeService {

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ProjectRepository projectRepository;
    private final ChangeService changeService;
    private final AuditRecorder auditRecorder;
    // 배포를 시작한 것이 에이전트 태스크이고 사용자가 결과를 기다리는 곳도 그 대화다. 상태만
    // 갱신하고 끝내면 사용자에게는 "접수했습니다"가 마지막 말로 남는다. deployment_histories 는
    // conversationId 를 갖고 있지 않아 taskId 로 태스크를 찾아 얻는다.
    private final AgentMessageService agentMessageService;
    private final TaskStore taskStore;

    @Transactional
    public void applySuccess(DeploymentHistory history, Project project) {
        history.complete();
        if (history.getTaskId() != null) {
            changeService.markDeployed(history.getTaskId());
        }
        updateProjectIfLatest(history, project, DeployStatus.LIVE);
        finish(history);
    }

    @Transactional
    public void applyFailure(DeploymentHistory history, Project project, String reason) {
        history.fail(reason);
        updateProjectIfLatest(history, project, DeployStatus.FAILED);
        finish(history);
    }

    private void updateProjectIfLatest(DeploymentHistory history, Project project, DeployStatus status) {
        if (!isLatestProjectDeployment(history)) {
            return;
        }
        project.updateDeployment(status, history.getDeployedUrl(), history.getVersionLabel());
        projectRepository.save(project);
    }

    private void finish(DeploymentHistory history) {
        deploymentHistoryRepository.save(history);
        notifyDeploymentOutcome(history);
        // actor SYSTEM — 직접 원인이 웹훅이나 워커이지 사용자 행동이 아니다. 추적을 위해
        // 배포 소유자에게 귀속시킨다 (design ADR-A8/H8).
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
     * 배포 결과를 시작한 대화에 알린다. 에이전트를 거치지 않은 배포는 알릴 대화가 없다.
     * taskId 가 없거나 태스크를 찾지 못하면 조용히 건너뛴다 — 배포 자체는 이미 확정됐으므로
     * 여기서 던져 호출한 쪽을 망가뜨릴 이유가 없다.
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

    private boolean isLatestProjectDeployment(DeploymentHistory history) {
        return deploymentHistoryRepository.findLatestByProjectId(history.getProjectId())
                .map(latest -> latest.getId().equals(history.getId()))
                .orElse(false);
    }
}
