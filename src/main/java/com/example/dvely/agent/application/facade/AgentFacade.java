package com.example.dvely.agent.application.facade;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.result.AgentSubmitResult;
import com.example.dvely.agent.application.service.AiModelOptionsResolver;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.domain.value.ThinkingLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentFacade {

    private final DecisionAgentService decisionAgentService;
    private final AgentOrchestrator agentOrchestrator;
    private final AiModelOptionsResolver modelOptionsResolver;

    public AgentSubmitResult submit(Long userId,
                                    Long requestedProjectId,
                                    Long conversationId,
                                    String content,
                                    AiProvider aiProvider) {
        return submit(userId, requestedProjectId, conversationId, content, aiProvider, null, null);
    }

    public AgentSubmitResult submit(Long userId,
                                    Long requestedProjectId,
                                    Long conversationId,
                                    String content,
                                    AiProvider aiProvider,
                                    String requestedModel,
                                    ThinkingLevel requestedThinking) {
        Long projectId = agentOrchestrator.resolveProjectId(
                userId,
                requestedProjectId,
                conversationId
        );
        // Resolved before the Decision call, not after: an unsupported model must fail this
        // request outright rather than after a paid LLM round-trip that the task then cannot use.
        AiModelOptions modelOptions = modelOptionsResolver.resolve(
                aiProvider, requestedModel, requestedThinking
        );
        AgentPlan plan = decisionAgentService.decide(content, aiProvider, projectId, modelOptions);
        AgentSubmission submission = agentOrchestrator.submit(plan, userId, conversationId);
        return new AgentSubmitResult(plan, submission);
    }
}
