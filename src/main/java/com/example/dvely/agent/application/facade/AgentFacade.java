package com.example.dvely.agent.application.facade;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.result.AgentSubmitResult;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentFacade {

    private final DecisionAgentService decisionAgentService;
    private final AgentOrchestrator    agentOrchestrator;

    public AgentSubmitResult submit(Long userId, Long projectId, String content, AiProvider aiProvider) {
        AgentPlan plan   = decisionAgentService.decide(content, aiProvider, projectId);
        String    taskId = agentOrchestrator.submitAsync(plan, userId);
        return new AgentSubmitResult(taskId, plan);
    }
}
