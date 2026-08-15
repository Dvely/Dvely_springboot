package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryNamePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * "저장소 연결 승인" 게이트. ResultApprovalGate 의 NOT_BOUND 짝이다.
 *
 * 두 게이트는 플랜의 마지막 CODE 스텝이 끝난 같은 지점에서 평가되며 같은 repositoryBindingStatus 를
 * 보고 갈린다. 저장소가 있으면 결과 승인 게이트가 "이 preview 를 main 에 반영할까"를 묻고, 없으면
 * 이 게이트가 "저장소를 만들어 연결할까"를 묻는다. 그래서 한 태스크에서 둘 중 하나만 발동한다.
 *
 * 이 게이트가 없던 시절 미연결 프로젝트의 CODE 작업물은 아무것도 묻지 않고 DONE 으로 끝났다.
 * 컨테이너는 TTL(기본 30분)로 삭제되고 작업물은 어디에도 남지 않으므로, 프리뷰가 만료되는 순간
 * 결과물이 영구 소실됐다. 자동으로 저장소를 만들어 주는 경로는 없었다. DeployAgentService 의
 * autoBindRepository 는 이름이 같은 기존 저장소를 찾아 연결만 하고, 없으면 예외를 던지며 설정
 * 화면에서 직접 연결하라고 안내한다.
 *
 * 왜 승인이고 WAITING_INPUT 이 아닌가:
 * 이 자리에서 WAITING_INPUT 으로 파킹하면 재개 시 AgentPlanExecutor 의 스텝 루프가 currentStep
 * 부터 다시 돈다. CODE 스텝을 완료 처리하지 않으면 LLM 이 코드를 재생성하고, 완료 처리하면 루프가
 * 한 번도 돌지 않아 답변을 처리할 자리가 사라진다. 승인은 결정 이후의 행동을 실행 루프 바깥
 * (ApprovalCommandService.approve)에서 수행하도록 되어 있어 이 문제가 없다.
 *
 * 왜 상태를 새로 만들지 않는가:
 * TaskStatus.WAITING_RESULT_APPROVAL 을 그대로 쓴다. 그 상태의 의미가 "게이트에서 사람의 결정을
 * 기다리는 중"이라 이 게이트와 성질이 같고, 워커가 집어갈 수 없으며 나가는 길이
 * resumeAfterResultApproval 하나뿐이라는 불변식을 이미 갖추고 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryBindingGate {

    private final ProjectRepository projectRepository;
    private final PreviewSessionService previewSessionService;
    private final TaskStore taskStore;
    private final ApprovalRepository approvalRepository;
    private final AgentMessageService agentMessageService;

    /**
     * 마지막 CODE 스텝이 끝난 직후, 결과 승인 게이트가 발동하지 않았을 때 호출된다.
     *
     * @param stepIndex plan.steps() 안에서 방금 끝난 CODE 스텝의 0-based 인덱스
     * @return 게이트가 발동했으면 true. 이때 호출자는 markStepCompleted 나 markDone 을 건드리지 말고
     *         즉시 실행을 중단해야 한다. 스텝 완료 처리는 이 메서드가 소유한다.
     */
    public boolean requestIfRequired(AgentPlan plan, int stepIndex, String taskId, Long userId, Long projectId) {
        if (!isLastCodeStep(plan, stepIndex) || projectId == null) {
            return false;
        }
        Project project = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElse(null);
        if (project == null || project.getRepositoryBindingStatus() == RepositoryBindingStatus.BOUND) {
            return false;
        }
        // 보존할 작업물이 실제로 있을 때만 묻는다. PreviewSession이 없으면 승인해도 push할 컨테이너가
        // 없어 빈 저장소만 만들게 된다 — 아무 일도 못 하는 승인은 열지 않는다(RESULT 게이트가
        // 미연결 프로젝트를 제외하는 것과 같은 이유).
        if (previewSessionService.findByTaskId(taskId).isEmpty()) {
            return false;
        }
        // ResultApprovalGate와 같은 취소 재확인 — 여기서 파킹하면 이미 취소된 태스크가 사람의 결정을
        // 기다리는 상태로 되살아난다.
        if (taskStore.isCancelled(taskId)) {
            log.info("[RepositoryBindingGate] 게이트 진입 시점에 이미 취소된 task — 게이트 생략 taskId={}", taskId);
            return false;
        }

        // 승인이 사용자에게 보이기 전에 태스크가 이미 resumeAfterResultApproval 의 가드를 통과할 수
        // 있는 상태여야 한다. 아니면 두 write 사이 짧은 창에 도착한 approve 가 아직 RUNNING 인
        // 태스크를 상대로 409 를 낸다.
        String candidate = RepositoryNamePolicy.forProject(project.getName(), userId);
        taskStore.markStepCompleted(taskId, stepIndex + 1);
        taskStore.markWaitingResultApproval(taskId, "[저장소 연결] " + candidate);

        AgentTask task = taskStore.get(taskId);
        Approval approval = approvalRepository.save(new Approval(
                userId,
                projectId,
                task == null ? null : task.conversationId(),
                taskId,
                ApprovalType.REPOSITORY_BINDING,
                "[저장소 연결] " + candidate
        ));
        log.info("[RepositoryBindingGate] 저장소 연결 승인 게이트 발동 | taskId={} approvalId={} projectId={}",
                taskId, approval.getId(), projectId);
        agentMessageService.appendAssistant(
                task == null ? null : task.conversationId(),
                buildGateMessage(task == null ? null : task.previewUrl(), approval, candidate)
        );
        return true;
    }

    private boolean isLastCodeStep(AgentPlan plan, int stepIndex) {
        if (plan.steps().get(stepIndex).agentType() != AgentType.CODE) {
            return false;
        }
        for (int i = stepIndex + 1; i < plan.steps().size(); i++) {
            AgentStep later = plan.steps().get(i);
            if (later.agentType() == AgentType.CODE) {
                return false;
            }
        }
        return true;
    }

    private String buildGateMessage(String previewUrl, Approval approval, String candidate) {
        return "작업물이 준비됐는데 이 프로젝트에는 아직 GitHub 저장소가 연결되어 있지 않습니다.\n"
                + "지금 연결하지 않으면 프리뷰가 만료될 때 작업물이 사라집니다.\n"
                + "승인하면 저장소를 만들어 연결하고 현재 작업물을 preview 브랜치에 올립니다.\n"
                + (previewUrl == null ? "" : "- preview: " + previewUrl + "\n")
                + "- 저장소 이름(기본값): " + candidate + " — 승인할 때 다른 이름을 보낼 수 있습니다.\n"
                + "- [" + approval.getId() + "] REPOSITORY_BINDING: " + approval.getSummary();
    }
}
