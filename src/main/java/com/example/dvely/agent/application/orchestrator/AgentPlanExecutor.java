package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.service.BuildFailureRecoveryService;
import com.example.dvely.agent.application.service.ChatAgentService;
import com.example.dvely.agent.application.service.CodeAgentService;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.application.service.DeployAgentService;
import com.example.dvely.agent.application.service.DomainBindAgentService;
import com.example.dvely.agent.application.service.InfraOpsAgentService;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.change.application.service.ChangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPlanExecutor {

    private final CodeAgentService       codeAgentService;
    private final DeployAgentService     deployAgentService;
    private final DomainBindAgentService domainBindAgentService;
    private final ChatAgentService       chatAgentService;
    private final InfraOpsAgentService   infraOpsAgentService;
    private final TaskStore              taskStore;
    private final AgentMessageService    agentMessageService;
    private final BuildFailureRecoveryService buildFailureRecoveryService;
    private final ChangeService changeService;

    @Async("agentExecutor")
    public void execute(AgentPlan plan, String taskId, Long userId) {
        log.info("=== AgentPlan 실행 시작: taskId={} | 총 {}단계 | reasoning={} ===",
                taskId, plan.steps().size(), plan.reasoning());

        try {
            AgentTask initialTask = taskStore.get(taskId);
            String previewUrl = initialTask == null ? null : initialTask.previewUrl();
            String summary = initialTask == null ? null : initialTask.summary();
            for (int i = taskStore.getCurrentStep(taskId); i < plan.steps().size(); i++) {
                if (taskStore.isCancelled(taskId)) {
                    log.info("=== AgentPlan 취소됨: taskId={} ===", taskId);
                    return;
                }
                AgentStep step = withSuggestedFix(plan.steps().get(i), taskId, userId);
                log.info("--- Step [{}/{}] agentType={} ---", i + 1, plan.steps().size(), step.agentType());
                CodeResult result = dispatch(step, plan.aiProvider(), userId, taskId, plan.projectId());
                if (taskStore.isCancelled(taskId)) {
                    log.info("=== AgentPlan step 완료 후 취소 확인: taskId={} ===", taskId);
                    return;
                }
                if (result != null) {
                    if (result.previewUrl() != null) previewUrl = result.previewUrl();
                    if (result.summary() != null)    summary    = result.summary();
                    taskStore.updateProgress(taskId, previewUrl, summary);
                    if (step.agentType() == com.example.dvely.agent.domain.value.AgentType.CODE) {
                        changeService.record(taskId, summary);
                    }
                }
                taskStore.markStepCompleted(taskId, i + 1);
            }
            if (taskStore.isCancelled(taskId)) {
                return;
            }
            taskStore.markDone(taskId, previewUrl, summary);
            taskStore.removePlan(taskId);
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    summary == null || summary.isBlank() ? "작업을 완료했습니다." : summary
            );
            log.info("=== AgentPlan 실행 완료: taskId={} | previewUrl={} ===", taskId, previewUrl);

        } catch (AgentInputRequiredException exception) {
            taskStore.markWaitingInput(taskId, exception.getMessage());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    exception.getMessage()
            );
            log.info("=== AgentPlan 사용자 입력 대기: taskId={} ===", taskId);
        } catch (CodeAgentExecutionException exception) {
            if (taskStore.isCancelled(taskId)) {
                return;
            }
            buildFailureRecoveryService.handle(taskId, exception);
            log.warn("=== AgentPlan build 실패 및 복구 대기: taskId={} ===", taskId);
        } catch (Exception e) {
            if (taskStore.isCancelled(taskId)) {
                log.info("=== AgentPlan 취소됨: taskId={} ===", taskId);
                return;
            }
            taskStore.markFailed(taskId, e.getMessage());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    "작업 중 오류가 발생했습니다: " + safeMessage(e)
            );
            log.error("=== AgentPlan 실행 실패: taskId={} ===", taskId, e);
        }
    }

    private AgentStep withSuggestedFix(AgentStep step, String taskId, Long userId) {
        if (step.agentType() != com.example.dvely.agent.domain.value.AgentType.CODE) {
            return step;
        }
        var failure = taskStore.getFailure(taskId, userId);
        if (failure == null
                || failure.attempt() == 0
                || failure.suggestedFix() == null
                || failure.suggestedFix().isBlank()) {
            return step;
        }
        java.util.Map<String, String> parameters = new java.util.HashMap<>(step.parameters());
        String instruction = parameters.getOrDefault("instruction", "");
        parameters.put(
                "instruction",
                instruction + "\n\n[재build 수정안]\n" + failure.suggestedFix()
        );
        return new AgentStep(step.agentType(), java.util.Map.copyOf(parameters));
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "알 수 없는 오류"
                : exception.getMessage();
    }

    private CodeResult dispatch(AgentStep step, com.example.dvely.agent.domain.value.AiProvider aiProvider, Long userId, String taskId, Long projectId) {
        return switch (step.agentType()) {
            case CODE          -> handleCode(step, aiProvider, userId, projectId, taskId);
            case DEPLOY        -> handleDeploy(step, userId, taskId, projectId);
            case DOMAIN_BIND   -> handleDomainBind(step, userId, taskId, projectId);
            case INFRA_OPERATE -> handleInfraOperate(step, userId, taskId, projectId);
            case CHAT          -> handleChat(step, aiProvider, taskId);
        };
    }

    private CodeResult handleCode(AgentStep step,
                                  com.example.dvely.agent.domain.value.AiProvider aiProvider,
                                  Long userId,
                                  Long projectId,
                                  String taskId) {
        log.info("[CODE 에이전트] 코드 작업 시작 | userId={} provider={} projectId={}", userId, aiProvider, projectId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        log.info("  targetFile  : {}", step.parameters().getOrDefault("targetFile", ""));
        return codeAgentService.execute(step, aiProvider, userId, projectId, taskId);
    }

    private CodeResult handleDeploy(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DEPLOY 에이전트] GitHub Pages 배포 시작 | userId={} projectId={}", userId, projectId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        log.info("  repoName    : {}", step.parameters().getOrDefault("repoName", ""));
        return deployAgentService.execute(step, userId, taskId, projectId);
    }

    private CodeResult handleDomainBind(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DOMAIN_BIND 에이전트] 도메인 연결 요청 수신 | userId={} projectId={}", userId, projectId);
        log.info("  domain      : {}", step.parameters().getOrDefault("domain", ""));
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        return domainBindAgentService.execute(step, userId, taskId, projectId);
    }

    private CodeResult handleInfraOperate(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[INFRA_OPERATE 에이전트] 인프라 운영 요청 수신 | userId={} projectId={}", userId, projectId);
        log.info("  operation   : {}", step.parameters().getOrDefault("operation", ""));
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        return infraOpsAgentService.execute(step, userId, taskId, projectId);
    }

    private CodeResult handleChat(AgentStep step, com.example.dvely.agent.domain.value.AiProvider aiProvider, String taskId) {
        log.info("[CHAT 에이전트] 대화 요청 수신 | provider={} taskId={}", aiProvider, taskId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        return chatAgentService.execute(step, aiProvider, taskId);
    }
}
