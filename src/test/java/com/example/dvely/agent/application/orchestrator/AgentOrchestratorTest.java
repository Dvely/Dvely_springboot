package com.example.dvely.agent.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {

    @Test
    void storesOwnerProjectAndConversationContext() {
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        TaskStore taskStore = new TaskStore();
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                executor,
                taskStore,
                projectRepository,
                conversationRepository
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        Project project = mock(Project.class);
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(project));
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.OPENAI, null);

        String taskId = orchestrator.submitAsync(plan, 1L, 21L);

        AgentTask task = taskStore.getOwned(taskId, 1L);
        assertThat(task.ownerUserId()).isEqualTo(1L);
        assertThat(task.projectId()).isEqualTo(11L);
        assertThat(task.conversationId()).isEqualTo(21L);
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        verify(executor).execute(
                new AgentPlan(List.of(), "reason", AiProvider.OPENAI, 11L),
                taskId,
                1L
        );
    }

    @Test
    void resolvesConversationProjectBeforeDecision() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(AgentPlanExecutor.class),
                new TaskStore(),
                projectRepository,
                conversationRepository
        );
        Conversation conversation = new Conversation(
                21L,
                1L,
                11L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 1L))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(11L, 1L))
                .thenReturn(Optional.of(mock(Project.class)));

        assertThat(orchestrator.resolveProjectId(1L, null, 21L)).isEqualTo(11L);
    }
}
