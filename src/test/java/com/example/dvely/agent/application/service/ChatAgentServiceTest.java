package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatAgentServiceTest {

    @Mock
    private LlmRouter llmRouter;

    @Mock
    private AgentMessageService agentMessageService;

    @Mock
    private TaskStore taskStore;

    @Mock
    private LlmPort llmPort;

    @Test
    void dropsTheOriginalUserTurnAndItsAckSinceInstructionIsARewriteOfThatSameTurn() {
        // Shape mirrors real production history for a first CHAT exchange: ChatCommandService
        // saves the raw user message, then AgentOrchestrator.submit() immediately appends the
        // "승인 정책에 따라 작업을 시작합니다." ack — both before this step ever runs (review F1).
        // Since `instruction` is Decision Agent's rewrite of that same raw user message, both
        // turns are redundant here and must be dropped rather than replayed alongside it.
        ChatAgentService service = new ChatAgentService(llmRouter, agentMessageService, taskStore);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "휴지통 보관 정책을 알려줘"));
        AgentTask task = new AgentTask(
                "task-1", 1L, 11L, 21L, TaskStatus.RUNNING, null, null, null, null, Instant.now()
        );
        List<LlmMessage> history = List.of(
                new LlmMessage("user", "휴지통 정책이 뭐야?"),
                new LlmMessage("assistant", "승인 정책에 따라 작업을 시작합니다.")
        );
        when(taskStore.get("task-1")).thenReturn(task);
        when(agentMessageService.getConversationContext(21L)).thenReturn(history);
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn("휴지통 보관 기간은 7일입니다.");

        var result = service.execute(step, AiProvider.ANTHROPIC, "task-1");

        assertThat(result.previewUrl()).isNull();
        assertThat(result.summary()).isEqualTo("휴지통 보관 기간은 7일입니다.");

        ArgumentCaptor<List<LlmMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).complete(any(), messagesCaptor.capture());
        // Both the redundant original user turn and the leading ack it left behind are dropped —
        // the model only sees Decision Agent's rewritten instruction (single, user-first turn).
        assertThat(messagesCaptor.getValue())
                .containsExactly(new LlmMessage("user", "휴지통 보관 정책을 알려줘"));
    }

    @Test
    void preservesGenuineEarlierRoundsWhileDroppingOnlyTheImmediateRedundantTurn() {
        // Second CHAT round in the same conversation: an earlier, unrelated exchange exists
        // before the current (redundant) user turn + its ack. Only the immediate duplicate pair
        // should be dropped; the earlier real history must survive so context is not lost.
        ChatAgentService service = new ChatAgentService(llmRouter, agentMessageService, taskStore);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "휴지통 보관 정책을 알려줘"));
        AgentTask task = new AgentTask(
                "task-2", 1L, 11L, 21L, TaskStatus.RUNNING, null, null, null, null, Instant.now()
        );
        LlmMessage earlierUser = new LlmMessage("user", "이 프로젝트는 뭐하는 앱이야?");
        LlmMessage earlierAssistant = new LlmMessage("assistant", "할 일 관리 앱입니다.");
        List<LlmMessage> history = List.of(
                earlierUser,
                earlierAssistant,
                new LlmMessage("user", "휴지통 정책이 뭐야?"),
                new LlmMessage("assistant", "승인 정책에 따라 작업을 시작합니다.")
        );
        when(taskStore.get("task-2")).thenReturn(task);
        when(agentMessageService.getConversationContext(21L)).thenReturn(history);
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn("휴지통 보관 기간은 7일입니다.");

        service.execute(step, AiProvider.ANTHROPIC, "task-2");

        ArgumentCaptor<List<LlmMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).complete(any(), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).containsExactly(
                earlierUser,
                earlierAssistant,
                new LlmMessage("assistant", "승인 정책에 따라 작업을 시작합니다."),
                new LlmMessage("user", "휴지통 보관 정책을 알려줘")
        );
    }

    @Test
    void answersWithInstructionOnlyWhenTaskHasNoConversation() {
        ChatAgentService service = new ChatAgentService(llmRouter, agentMessageService, taskStore);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "안녕 잘 지내?"));
        AgentTask task = new AgentTask(
                "task-2", 1L, null, null, TaskStatus.RUNNING, null, null, null, null, Instant.now()
        );
        when(taskStore.get("task-2")).thenReturn(task);
        when(llmRouter.route(AiProvider.OPENAI)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenReturn("네, 잘 지내고 있어요!");

        var result = service.execute(step, AiProvider.OPENAI, "task-2");

        assertThat(result.summary()).isEqualTo("네, 잘 지내고 있어요!");
        verify(agentMessageService, never()).getConversationContext(any());

        ArgumentCaptor<List<LlmMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).complete(any(), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).containsExactly(new LlmMessage("user", "안녕 잘 지내?"));
    }

    @Test
    void propagatesLlmFailureForAgentPlanExecutorToHandleUniformly() {
        // No local try/catch by design (see ChatAgentService javadoc): a transport failure must
        // surface unchanged so AgentPlanExecutor's own catch(Exception) can markFailed(...) and
        // append the standard assistant error message, exactly like the other agent services.
        ChatAgentService service = new ChatAgentService(llmRouter, agentMessageService, taskStore);
        AgentStep step = new AgentStep(AgentType.CHAT, Map.of("instruction", "질문"));
        AgentTask task = new AgentTask(
                "task-3", 1L, null, null, TaskStatus.RUNNING, null, null, null, null, Instant.now()
        );
        when(taskStore.get("task-3")).thenReturn(task);
        when(llmRouter.route(AiProvider.ANTHROPIC)).thenReturn(llmPort);
        when(llmPort.complete(any(), anyList())).thenThrow(new IllegalStateException("Claude API 응답이 비어있습니다"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.execute(step, AiProvider.ANTHROPIC, "task-3")
        );
    }
}
