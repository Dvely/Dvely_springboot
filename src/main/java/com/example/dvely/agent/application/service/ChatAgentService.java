package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Executes the CHAT step of an {@link com.example.dvely.agent.application.dto.AgentPlan} — i.e.
 * whatever DecisionAgentService could not classify as CODE/DEPLOY/DOMAIN_BIND (general questions,
 * clarifications, small talk). Unlike the other *AgentService classes this one never touches
 * Docker/GitHub/deployment infrastructure: it only calls the LLM once and returns the answer as
 * the step summary, which AgentPlanExecutor then persists as the assistant's chat reply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAgentService {

    private final LlmRouter llmRouter;
    private final AgentMessageService agentMessageService;
    private final TaskStore taskStore;

    // Written in English to match DecisionAgentService's system-prompt convention, but instructs
    // the model to answer in Korean since that is the product's user-facing language.
    private static final String SYSTEM_PROMPT = """
            You are the Qeploy platform assistant, chatting with a user inside a chat-driven
            web-app builder. Qeploy lets users describe what they want in natural language, and
            an agent pipeline generates code, deploys it to GitHub Pages, and binds custom domains.

            Answer the user's question or general request directly and concisely, in Korean.
            If the request actually requires the platform to take action (generate or modify
            code, deploy, or connect a domain), tell the user to ask for that action explicitly
            (e.g. "이 기능을 추가해줘", "배포해줘", "도메인을 연결해줘") — this step only answers,
            it does not perform CODE/DEPLOY/DOMAIN_BIND work itself.
            Keep the tone helpful and professional; do not just repeat the question back.
            """;

    public CodeResult execute(AgentStep step, AiProvider provider, String taskId) {
        String instruction = step.parameters().getOrDefault("instruction", "");
        log.info("[ChatAgent] 실행 시작 | provider={} taskId={}", provider, taskId);
        log.info("  instruction : {}", instruction);

        // conversationId is task-scoped metadata (see AgentTask), not part of AgentStep/AgentPlan
        // itself, so it is resolved through TaskStore rather than threaded through the plan.
        // Reusing the same history DecisionAgentService saw keeps the answer consistent with
        // what the user already discussed in this conversation.
        AgentTask task = taskStore.get(taskId);
        Long conversationId = task == null ? null : task.conversationId();

        List<LlmMessage> messages = new ArrayList<>(
                conversationId == null ? List.of() : agentMessageService.getConversationContext(conversationId)
        );
        messages.add(new LlmMessage("user", instruction));

        // No local try/catch: LLM transport failures surface as RuntimeExceptions (see
        // ClaudeClient/OpenAiClient), which AgentPlanExecutor's own catch(Exception) already
        // turns into markFailed(...) + a user-facing assistant error message. Catching here too
        // would duplicate that handling and diverge from how CodeAgentService/DeployAgentService/
        // DomainBindAgentService let non-recoverable errors propagate the same way.
        String answer = llmRouter.route(provider).complete(SYSTEM_PROMPT, messages);
        log.info("[ChatAgent] 완료 | taskId={}", taskId);
        return new CodeResult(null, answer);
    }
}
