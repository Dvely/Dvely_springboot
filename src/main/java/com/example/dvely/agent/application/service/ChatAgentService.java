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

        List<LlmMessage> history = conversationId == null
                ? List.of()
                : agentMessageService.getConversationContext(conversationId);

        List<LlmMessage> messages = new ArrayList<>(withoutRedundantUserTurn(history));
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

    /**
     * {@code instruction} is Decision Agent's self-contained rewrite of the most recent user
     * message in this conversation (its system prompt asks for "a complete, self-contained
     * natural language request ... as if the original message did not exist"). Replaying that
     * original message unchanged and then appending {@code instruction} as a new turn would show
     * the model the same request twice in different words — wasted tokens and an odd-reading
     * transcript. So we drop the most recent USER turn here; {@code instruction} takes its place
     * as the new final user turn.
     *
     * <p>Anthropic's Messages API additionally requires the turn sequence to start with role
     * "user". If dropping that turn leaves a leading run of assistant turns with nothing before
     * them — the common case for a conversation's first exchange, where history is just
     * {@code [user, assistant-ack]} and becomes {@code [assistant-ack]} — we drop that leading
     * run too rather than send an assistant-first payload the API would reject. Any genuine
     * earlier conversation history (from previous rounds) is preserved as-is.</p>
     */
    private List<LlmMessage> withoutRedundantUserTurn(List<LlmMessage> history) {
        int lastUserIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex == -1) {
            return history;
        }

        List<LlmMessage> withoutLastUserTurn = new ArrayList<>(history);
        withoutLastUserTurn.remove(lastUserIndex);

        int firstUserIndex = 0;
        while (firstUserIndex < withoutLastUserTurn.size()
                && !"user".equals(withoutLastUserTurn.get(firstUserIndex).role())) {
            firstUserIndex++;
        }
        return withoutLastUserTurn.subList(firstUserIndex, withoutLastUserTurn.size());
    }
}
