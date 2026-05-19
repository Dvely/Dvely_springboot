package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.CodeAgentService;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.application.service.DeployAgentService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPlanExecutor {

    private final CodeAgentService   codeAgentService;
    private final DeployAgentService deployAgentService;
    private final TaskStore          taskStore;

    @Async("agentExecutor")
    public void execute(AgentPlan plan, String taskId, Long userId) {
        log.info("=== AgentPlan 실행 시작: taskId={} | 총 {}단계 | reasoning={} ===",
                taskId, plan.steps().size(), plan.reasoning());

        taskStore.save(new AgentTask(taskId, TaskStatus.RUNNING, null, null, null, null, Instant.now()));

        try {
            String previewUrl = null;
            String summary    = null;
            for (int i = 0; i < plan.steps().size(); i++) {
                AgentStep step = plan.steps().get(i);
                log.info("--- Step [{}/{}] agentType={} ---", i + 1, plan.steps().size(), step.agentType());
                CodeResult result = dispatch(step, plan.aiProvider(), userId, taskId, plan.projectId());
                if (result != null) {
                    if (result.previewUrl() != null) previewUrl = result.previewUrl();
                    if (result.summary() != null)    summary    = result.summary();
                }
            }
            taskStore.markDone(taskId, previewUrl, summary);
            log.info("=== AgentPlan 실행 완료: taskId={} | previewUrl={} ===", taskId, previewUrl);

        } catch (Exception e) {
            taskStore.markFailed(taskId, e.getMessage());
            log.error("=== AgentPlan 실행 실패: taskId={} ===", taskId, e);
        }
    }

    private CodeResult dispatch(AgentStep step, com.example.dvely.agent.domain.value.AiProvider aiProvider, Long userId, String taskId, Long projectId) {
        return switch (step.agentType()) {
            case CODE        -> handleCode(step, aiProvider, userId, projectId);
            case DEPLOY      -> handleDeploy(step, userId, taskId, projectId);
            case DOMAIN_BIND -> handleDomainBind(step);
            case CHAT        -> handleChat(step);
        };
    }

    private CodeResult handleCode(AgentStep step, com.example.dvely.agent.domain.value.AiProvider aiProvider, Long userId, Long projectId) {
        log.info("[CODE 에이전트] 코드 작업 시작 | userId={} provider={} projectId={}", userId, aiProvider, projectId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        log.info("  targetFile  : {}", step.parameters().getOrDefault("targetFile", ""));
        return codeAgentService.execute(step, aiProvider, userId, projectId);
    }

    private CodeResult handleDeploy(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DEPLOY 에이전트] GitHub Pages 배포 시작 | userId={} projectId={}", userId, projectId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        log.info("  repoName    : {}", step.parameters().getOrDefault("repoName", ""));
        return deployAgentService.execute(step, userId, taskId, projectId);
    }

    private CodeResult handleDomainBind(AgentStep step) {
        log.info("[DOMAIN_BIND 에이전트] 도메인 연결 요청 수신");
        log.info("  domain      : {}", step.parameters().getOrDefault("domain", ""));
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        // TODO: DomainBindingAgentService.execute(step) 연결 예정
        return null;
    }

    private CodeResult handleChat(AgentStep step) {
        log.info("[CHAT 에이전트] 대화 요청 수신");
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        // TODO: ChatAgentService.execute(step) 연결 예정
        return null;
    }
}
