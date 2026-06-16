package com.example.dvely.agent.application.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.result.AgentSubmitResult;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentFacadeTest {

    @Mock
    private DecisionAgentService decisionAgentService;

    @Mock
    private AgentOrchestrator agentOrchestrator;

    private AgentFacade agentFacade;

    @BeforeEach
    void setUp() {
        agentFacade = new AgentFacade(decisionAgentService, agentOrchestrator);
    }

    @Test
    void resolvesProjectBeforeDecisionAndSubmitsWithConversationContext() {
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.CODE, Map.of("instruction", "수정"))),
                "reason",
                AiProvider.OPENAI,
                11L
        );
        AgentSubmission submission = new AgentSubmission(
                "task-1",
                TaskStatus.WAITING_APPROVAL,
                List.of(101L)
        );
        when(agentOrchestrator.resolveProjectId(1L, null, 21L)).thenReturn(11L);
        when(decisionAgentService.decide("수정해줘", AiProvider.OPENAI, 11L)).thenReturn(plan);
        when(agentOrchestrator.submit(plan, 1L, 21L)).thenReturn(submission);

        AgentSubmitResult result = agentFacade.submit(
                1L,
                null,
                21L,
                "수정해줘",
                AiProvider.OPENAI
        );

        assertThat(result.plan()).isEqualTo(plan);
        assertThat(result.submission()).isEqualTo(submission);
        verify(agentOrchestrator).resolveProjectId(1L, null, 21L);
        verify(decisionAgentService).decide("수정해줘", AiProvider.OPENAI, 11L);
        verify(agentOrchestrator).submit(plan, 1L, 21L);
    }
}
