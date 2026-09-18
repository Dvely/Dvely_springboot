package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.exception.AgentTokenBudgetExceededException;
import com.example.dvely.agent.application.exception.CodeAgentExecutionException;
import com.example.dvely.agent.application.service.BuildFailureRecoveryService;
import com.example.dvely.agent.application.service.ChatAgentService;
import com.example.dvely.agent.application.service.CodeAgentService;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.application.service.DeployAgentService;
import com.example.dvely.agent.application.service.DomainBindAgentService;
import com.example.dvely.agent.application.service.InfraOpsAgentService;
import com.example.dvely.agent.application.service.RuntimeSetupAgentService;
import com.example.dvely.agent.application.service.BackendDeployAgentService;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.RepositoryBindingGate;
import com.example.dvely.agent.application.service.ResultApprovalGate;
import com.example.dvely.agent.application.dto.ClarificationRequest;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.domain.value.AgentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import com.example.dvely.agent.infrastructure.usage.LlmUsageScope;
import com.example.dvely.agent.infrastructure.worker.AgentExecutionRegistry;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.common.exception.LlmProviderException;
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
    private final RuntimeSetupAgentService runtimeSetupAgentService;
    private final BackendDeployAgentService backendDeployAgentService;
    private final TaskStore              taskStore;
    private final AgentMessageService    agentMessageService;
    private final BuildFailureRecoveryService buildFailureRecoveryService;
    private final ChangeService changeService;
    private final ResultApprovalGate resultApprovalGate;
    private final RepositoryBindingGate repositoryBindingGate;
    // ADR-Y4 (#55): paired with AgentRunWorker's register-before-submit call — see
    // AgentExecutionRegistry's javadoc for why registration itself must NOT happen here.
    private final AgentExecutionRegistry executionRegistry;
    private final DecisionAgentService decisionAgentService;   // 되묻기 답 반영 재-decide
    private final InputWaitStore inputWaitStore;               // CLARIFY 답 consume
    private final ObjectMapper objectMapper;                   // CLARIFY 구조화 질문 파싱
    private final LlmUsageRecorder llmUsageRecorder;           // 태스크 토큰 계측·예산 스코프
    private final AiProperties aiProperties;                   // 태스크당 토큰 상한

    @Async("agentExecutor")
    public void execute(AgentPlan plan, String taskId, Long userId) {
        // 이 실행 스레드에서 나는 모든 LLM 호출이 이 태스크에 귀속되고, 누적 토큰이 여기 상한에
        // 걸린다. 스코프는 스레드를 넘지 않으므로 실행 진입점인 여기가 유일하게 맞는 자리다.
        try (LlmUsageScope ignored = llmUsageRecorder.openTaskScope(
                taskId, userId, plan.projectId(), aiProperties.getCodeAgent().getMaxTaskTokens())) {
            doExecute(plan, taskId, userId);
        } finally {
            // The only unregister site for a task that made it onto an executor thread — covers
            // every exit path below (normal completion and every catch branch) uniformly, so the
            // registry can never leak an entry for a task whose executor thread has actually
            // finished one way or another.
            executionRegistry.unregister(taskId);
        }
    }

    private void doExecute(AgentPlan plan, String taskId, Long userId) {
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
                taskStore.appendStepEvent(
                        taskId, "STEP_STARTED", com.example.dvely.agent.application.dto.TaskStatus.RUNNING,
                        stepProgressMessage(step.agentType(), i + 1, plan.steps().size()),
                        i + 1, plan.steps().size(), step.agentType().name());
                if (step.agentType() == AgentType.CLARIFY) {
                    // 답이 없으면 던져서 WAITING_INPUT, 있으면 재-decide 후 재큐한다 — 어느 쪽이든 이 실행은 종료.
                    handleClarify(step, plan, taskId, initialTask);
                    return;
                }
                CodeResult result = dispatch(step, plan.aiProvider(), plan.modelOptions(), userId, taskId, plan.projectId());
                if (taskStore.isCancelled(taskId)) {
                    log.info("=== AgentPlan step 완료 후 취소 확인: taskId={} ===", taskId);
                    return;
                }
                if (result != null) {
                    if (result.previewUrl() != null) previewUrl = result.previewUrl();
                    if (result.summary() != null)    summary    = result.summary();
                    taskStore.updateProgress(taskId, previewUrl, summary);
                    if (step.agentType() == AgentType.CODE) {
                        changeService.record(taskId, summary);
                        // Track Z (#56): the gate owns markStepCompleted for the CODE step it
                        // fires on (see ResultApprovalGate javadoc for why — push must succeed
                        // before the step is considered "done" so a push failure leaves the CODE
                        // step retryable). When it does not fire (policy OFF, unbound project, or
                        // not the plan's last CODE step) it makes no writes at all, and the normal
                        // markStepCompleted below runs exactly as it did before this feature.
                        if (resultApprovalGate.requestIfRequired(plan, i, taskId, userId, plan.projectId())) {
                            log.info("=== AgentPlan 결과 승인 대기: taskId={} ===", taskId);
                            return;
                        }
                        // 위 게이트가 발동하지 않은 경우에만 평가된다. 두 게이트는 같은
                        // repositoryBindingStatus 를 보고 갈리므로(BOUND -> 결과 승인,
                        // NOT_BOUND -> 저장소 연결) 한 태스크에서 둘 다 발동하는 일은 없다.
                        // 이 게이트도 자기가 발동한 CODE 스텝의 markStepCompleted 를 직접 소유한다.
                        if (repositoryBindingGate.requestIfRequired(plan, i, taskId, userId, plan.projectId())) {
                            log.info("=== AgentPlan 저장소 연결 승인 대기: taskId={} ===", taskId);
                            return;
                        }
                    }
                }
                taskStore.appendStepEvent(
                        taskId, "STEP_COMPLETED", com.example.dvely.agent.application.dto.TaskStatus.RUNNING,
                        stepDoneMessage(step.agentType(), i + 1, plan.steps().size()),
                        i + 1, plan.steps().size(), step.agentType().name());
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
                    summary == null || summary.isBlank() ? "작업을 완료했습니다." : summary,
                    ChatMessageKind.AGENT_RESULT,
                    taskId
            );
            log.info("=== AgentPlan 실행 완료: taskId={} | previewUrl={} ===", taskId, previewUrl);

        } catch (AgentInputRequiredException exception) {
            taskStore.markWaitingInput(taskId, exception.getMessage(), exception.getClarification());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    exception.getMessage(),
                    ChatMessageKind.INPUT_REQUIRED,
                    taskId
            );
            log.info("=== AgentPlan 사용자 입력 대기: taskId={} ===", taskId);
        } catch (CodeAgentExecutionException exception) {
            if (taskStore.isCancelled(taskId)) {
                return;
            }
            buildFailureRecoveryService.handle(taskId, exception);
            log.warn("=== AgentPlan build 실패 및 복구 대기: taskId={} ===", taskId);
        } catch (AgentTokenBudgetExceededException exception) {
            // 상한에 걸린 태스크가 조용히 멈추면 사용자에게는 "왜 안 되지" 로만 남는다. 아래
            // catch-all 로 흘리면 "작업 중 오류가 발생했습니다" 가 앞에 붙어, 사용자가 읽어야 할
            // 단 하나의 문장(무엇에 걸렸고 무엇을 하면 되는지)이 묻힌다. 그래서 전용 분기다.
            //
            // 재시도로 흘리지 않는 것도 의도다 — 누적은 태스크 단위로 이어 세므로, 재시도해도
            // 첫 호출에서 곧바로 같은 상한에 다시 걸린다.
            if (taskStore.isCancelled(taskId)) {
                return;
            }
            taskStore.markFailed(taskId, exception.getMessage());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    exception.getMessage(),
                    ChatMessageKind.TASK_FAILED,
                    taskId
            );
            log.warn("=== AgentPlan 토큰 예산 초과로 중단: taskId={} used={} budget={} ===",
                    taskId, exception.usedTokens(), exception.budgetTokens());
        } catch (LlmProviderException exception) {
            // Separated from the catch-all below only for the chat reply: the provider message is
            // already a complete, actionable sentence ("... 크레딧이 부족해 ... 다른 AI 제공자를
            // 선택하거나 결제 상태를 확인해주세요"), and prefixing it with "작업 중 오류가
            // 발생했습니다" would bury the one instruction the user can act on.
            if (taskStore.isCancelled(taskId)) {
                return;
            }
            taskStore.markFailed(taskId, exception.getMessage());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    exception.getMessage(),
                    ChatMessageKind.TASK_FAILED,
                    taskId
            );
            log.error("=== AgentPlan AI 제공자 실패: taskId={} provider={} reason={} ===",
                    taskId, exception.providerName(), exception.reason());
        } catch (Exception e) {
            if (taskStore.isCancelled(taskId)) {
                log.info("=== AgentPlan 취소됨: taskId={} ===", taskId);
                return;
            }
            taskStore.markFailed(taskId, e.getMessage());
            AgentTask task = taskStore.get(taskId);
            agentMessageService.appendAssistant(
                    task == null ? null : task.conversationId(),
                    "작업 중 오류가 발생했습니다: " + safeMessage(e),
                    ChatMessageKind.TASK_FAILED,
                    taskId
            );
            log.error("=== AgentPlan 실행 실패: taskId={} ===", taskId, e);
        }
    }

    private AgentStep withSuggestedFix(AgentStep step, String taskId, Long userId) {
        if (step.agentType() != AgentType.CODE) {
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

    private CodeResult dispatch(AgentStep step,
                                com.example.dvely.agent.domain.value.AiProvider aiProvider,
                                AiModelOptions modelOptions,
                                Long userId,
                                String taskId,
                                Long projectId) {
        return switch (step.agentType()) {
            case CODE          -> handleCode(step, aiProvider, modelOptions, userId, projectId, taskId);
            case DEPLOY        -> handleDeploy(step, userId, taskId, projectId);
            case DOMAIN_BIND   -> handleDomainBind(step, userId, taskId, projectId);
            case INFRA_OPERATE -> handleInfraOperate(step, userId, taskId, projectId);
            case RUNTIME_SETUP -> handleRuntimeSetup(step, userId, projectId);
            case BACKEND_DEPLOY -> handleBackendDeploy(step, userId, taskId, projectId);
            case CHAT          -> handleChat(step, aiProvider, modelOptions, taskId);
            // CLARIFY 는 dispatch 이전(루프)에서 처리된다 — 여기 오면 로직 오류.
            case CLARIFY       -> throw new IllegalStateException("CLARIFY 는 dispatch 앞에서 처리되어야 한다");
        };
    }

    /**
     * 스펙 되묻기(CLARIFY) 처리. 아직 답이 없으면 {@link AgentInputRequiredException}(구조화 질문)을 던져
     * WAITING_INPUT 으로 멈춘다. 답이 있으면 <b>그 답으로 재-decide</b>(CLARIFY 재출력 금지)해 스택이 일관된
     * 새 플랜을 만들고, 플랜을 교체·재큐한다 — 워커가 새 플랜을 처음부터 실행한다. 답을 CODE 지시문에만 끼워
     * 넣지 않고 재-decide 하는 이유: 스택 선택은 RUNTIME_SETUP·CODE·BACKEND_DEPLOY 를 함께 바꿔야 일관되다.
     */
    /**
     * 진행 중 문구. 로그가 아니라 <b>사용자가 읽는 줄</b>이라, 내부 용어(컨테이너·워크스페이스·
     * 브랜치) 대신 무엇이 되고 있는지를 말한다. 여러 단계짜리 계획이면 몇 번째인지도 붙인다 —
     * "언제 끝나나" 를 가늠할 수 있는 유일한 단서다.
     */
    private String stepProgressMessage(AgentType type, int index, int total) {
        return withProgress(switch (type) {
            case CODE           -> "코드를 만들고 있습니다";
            case DEPLOY         -> "배포하고 있습니다";
            case DOMAIN_BIND    -> "도메인을 연결하고 있습니다";
            case INFRA_OPERATE  -> "서버 작업을 진행하고 있습니다";
            case RUNTIME_SETUP  -> "실행 환경을 준비하고 있습니다";
            case BACKEND_DEPLOY -> "백엔드를 배포하고 있습니다";
            case CHAT           -> "답변을 준비하고 있습니다";
            case CLARIFY        -> "확인이 필요한 내용을 정리하고 있습니다";
        }, index, total);
    }

    private String stepDoneMessage(AgentType type, int index, int total) {
        return withProgress(switch (type) {
            case CODE           -> "코드 작업을 마쳤습니다";
            case DEPLOY         -> "배포 요청을 접수했습니다";
            case DOMAIN_BIND    -> "도메인 연결을 마쳤습니다";
            case INFRA_OPERATE  -> "서버 작업을 마쳤습니다";
            case RUNTIME_SETUP  -> "실행 환경을 준비했습니다";
            case BACKEND_DEPLOY -> "백엔드 배포 요청을 접수했습니다";
            case CHAT           -> "답변을 마쳤습니다";
            case CLARIFY        -> "확인 내용을 정리했습니다";
        }, index, total);
    }

    /** 한 단계짜리 계획에 "(1/1)" 을 붙이면 군더더기다. */
    private String withProgress(String text, int index, int total) {
        return total <= 1 ? text : text + " (" + index + "/" + total + ")";
    }

    private void handleClarify(AgentStep step, AgentPlan plan, String taskId, AgentTask task) {
        ClarificationRequest request = parseClarification(step);
        Optional<String> answer = inputWaitStore.consume(taskId);
        if (answer.isEmpty()) {
            throw new AgentInputRequiredException(request);
        }
        Long conversationId = task == null ? null : task.conversationId();
        List<LlmMessage> conversation = conversationId == null
                ? new ArrayList<>()
                : new ArrayList<>(agentMessageService.getUserIntentHistory(conversationId));
        conversation.add(new LlmMessage("assistant", request.question()));
        conversation.add(new LlmMessage("user", answer.get()));
        AgentPlan replanned = decisionAgentService.decide(
                conversation, plan.aiProvider(), plan.projectId(), plan.modelOptions(), false);
        taskStore.replacePlanAndRequeue(taskId, replanned);
        log.info("=== 스펙 되묻기 답 반영 → 재계획: taskId={} newSteps={} ===",
                taskId, replanned.steps().stream().map(AgentStep::agentType).toList());
    }

    private ClarificationRequest parseClarification(AgentStep step) {
        try {
            return objectMapper.readValue(
                    step.parameters().getOrDefault("clarification", ""), ClarificationRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("CLARIFY 스텝의 clarification 파싱 실패", e);
        }
    }

    private CodeResult handleCode(AgentStep step,
                                  com.example.dvely.agent.domain.value.AiProvider aiProvider,
                                  AiModelOptions modelOptions,
                                  Long userId,
                                  Long projectId,
                                  String taskId) {
        log.info("[CODE 에이전트] 코드 작업 시작 | userId={} provider={} projectId={}", userId, aiProvider, projectId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        log.info("  targetFile  : {}", step.parameters().getOrDefault("targetFile", ""));
        return codeAgentService.execute(step, aiProvider, userId, projectId, taskId, modelOptions);
    }

    private CodeResult handleDeploy(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DEPLOY 에이전트] 배포 시작 | userId={} projectId={} hostingType={}",
                userId, projectId, step.parameters().getOrDefault("hostingType", "(프로젝트 설정 유지)"));
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

    private CodeResult handleRuntimeSetup(AgentStep step, Long userId, Long projectId) {
        log.info("[RUNTIME_SETUP 에이전트] 런타임 설정 요청 | userId={} projectId={} runtimeType={} dbEngine={}",
                userId, projectId, step.parameters().getOrDefault("runtimeType", ""),
                step.parameters().getOrDefault("dbEngine", ""));
        return runtimeSetupAgentService.execute(step, userId, projectId);
    }

    private CodeResult handleBackendDeploy(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[BACKEND_DEPLOY 에이전트] 운영 백엔드 배포 요청 | userId={} projectId={} instanceType={} dbEngine={}",
                userId, projectId, step.parameters().getOrDefault("instanceType", ""),
                step.parameters().getOrDefault("dbEngine", ""));
        // 배포 승인(DB/서버)에 대화 id 를 실어 채팅이 대화 스코프로 그 승인을 찾아 카드로 띄우게 한다
        // (배포 e2e 발견 #1 저위험 1단계). 승인은 여전히 standalone 이라 라우팅은 불변.
        AgentTask task = taskStore.get(taskId);
        Long conversationId = task == null ? null : task.conversationId();
        return backendDeployAgentService.execute(step, userId, projectId, conversationId, taskId);
    }

    private CodeResult handleChat(AgentStep step, com.example.dvely.agent.domain.value.AiProvider aiProvider, AiModelOptions modelOptions, String taskId) {
        log.info("[CHAT 에이전트] 대화 요청 수신 | provider={} taskId={}", aiProvider, taskId);
        log.info("  instruction : {}", step.parameters().getOrDefault("instruction", ""));
        return chatAgentService.execute(step, aiProvider, taskId, modelOptions);
    }
}
