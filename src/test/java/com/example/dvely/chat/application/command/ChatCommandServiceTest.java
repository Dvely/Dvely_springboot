package com.example.dvely.chat.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatCommandServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DecisionAgentService decisionAgentService;

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private AgentMessageService agentMessageService;

    @InjectMocks
    private ChatCommandService chatCommandService;

    @Test
    void restoreConversation_rebindsToActiveProjectWithSameSourceRepositoryWhenOriginalProjectWasDeleted() {
        Conversation conversation = new Conversation(
                11L,
                2L,
                7L,
                true,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now().minusDays(1)
        );
        Project deletedProject = project(7L, 2L, "otter/sample-repo", true);
        Project activeProject = project(15L, 2L, "otter/sample-repo", false);

        when(conversationRepository.findByIdAndUserId(11L, 2L)).thenReturn(Optional.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(7L, 2L)).thenReturn(Optional.empty());
        when(projectRepository.findByIdAndOwnerUserId(7L, 2L)).thenReturn(Optional.of(deletedProject));
        when(projectRepository.findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                2L,
                "otter/sample-repo"
        )).thenReturn(Optional.of(activeProject));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationResult result = chatCommandService.restoreConversation(2L, 11L);

        assertThat(result.projectId()).isEqualTo(15L);
        assertThat(result.deleted()).isFalse();
        assertThat(result.deletedAt()).isNull();
        verify(conversationRepository).save(conversation);
    }

    @Test
    void sendMessageStartsAgentUsingConversationContext() {
        Conversation conversation = new Conversation(
                21L,
                2L,
                7L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ChatMessage saved = new ChatMessage(
                31L,
                21L,
                com.example.dvely.chat.domain.value.ChatRole.USER,
                "FAQ를 추가해줘",
                0,
                LocalDateTime.now()
        );
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        AgentPlan plan = new AgentPlan(List.of(), "reason", AiProvider.ANTHROPIC, 7L);
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 2L))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
        when(agentMessageService.getConversationContext(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L)).thenReturn(plan);

        MessageResult result = chatCommandService.sendMessage(2L, 21L, "FAQ를 추가해줘");

        assertThat(result.messageId()).isEqualTo(31L);
        assertThat(conversation.getTitle()).isEqualTo("FAQ를 추가해줘");
        verify(conversationRepository).save(conversation);
        verify(agentOrchestrator).submit(plan, 2L, 21L);
    }

    @Test
    void restoreConversationRejectsConversationAfterSevenDays() {
        Conversation conversation = new Conversation(
                11L,
                2L,
                7L,
                true,
                LocalDateTime.now().minusDays(7).minusMinutes(1),
                LocalDateTime.now().minusDays(10),
                LocalDateTime.now().minusDays(7)
        );
        when(conversationRepository.findByIdAndUserId(11L, 2L)).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> chatCommandService.restoreConversation(2L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7 days");
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void permanentlyDeleteConversationDeletesOnlyOwnedTrashConversation() {
        Conversation conversation = new Conversation(
                11L,
                2L,
                7L,
                true,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now().minusDays(1)
        );
        when(conversationRepository.findByIdAndUserId(11L, 2L)).thenReturn(Optional.of(conversation));

        chatCommandService.permanentlyDeleteConversation(2L, 11L);

        verify(conversationRepository).deleteById(11L);
    }

    @Test
    void permanentlyDeleteConversationRejectsActiveConversation() {
        Conversation conversation = new Conversation(
                11L,
                2L,
                7L,
                false,
                null,
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now()
        );
        when(conversationRepository.findByIdAndUserId(11L, 2L)).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> chatCommandService.permanentlyDeleteConversation(2L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("휴지통");
        verify(conversationRepository, never()).deleteById(any());
    }

    @Test
    void purgeExpiredConversationsDeletesRepositoryMatches() {
        Conversation first = new Conversation(
                11L,
                2L,
                7L,
                true,
                LocalDateTime.now().minusDays(8),
                LocalDateTime.now().minusDays(10),
                LocalDateTime.now().minusDays(8)
        );
        Conversation second = new Conversation(
                12L,
                2L,
                7L,
                true,
                LocalDateTime.now().minusDays(9),
                LocalDateTime.now().minusDays(10),
                LocalDateTime.now().minusDays(9)
        );
        when(conversationRepository.findAllByDeletedTrueAndDeletedAtLessThanEqual(any()))
                .thenReturn(List.of(first, second));

        assertThat(chatCommandService.purgeExpiredConversations()).isEqualTo(2);
        verify(conversationRepository).deleteById(11L);
        verify(conversationRepository).deleteById(12L);
    }

    @Test
    void sendMessageStoresAssistantErrorWhenDecisionFails() {
        Conversation conversation = new Conversation(
                21L,
                2L,
                7L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ChatMessage saved = new ChatMessage(
                31L,
                21L,
                com.example.dvely.chat.domain.value.ChatRole.USER,
                "FAQ를 추가해줘",
                0,
                LocalDateTime.now()
        );
        List<LlmMessage> context = List.of(new LlmMessage("user", "FAQ를 추가해줘"));
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 2L))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
        when(agentMessageService.getConversationContext(21L)).thenReturn(context);
        when(decisionAgentService.decide(context, AiProvider.ANTHROPIC, 7L))
                .thenThrow(new IllegalStateException("LLM 연결 실패"));

        chatCommandService.sendMessage(2L, 21L, "FAQ를 추가해줘");

        verify(agentMessageService).appendAssistant(
                21L,
                "요청을 분석하지 못했습니다: LLM 연결 실패"
        );
    }

    private Project project(Long projectId, Long ownerUserId, String sourceRepository, boolean deleted) {
        return new Project(
                projectId,
                ownerUserId,
                "sample",
                deleted ? ProjectStatus.ARCHIVED : ProjectStatus.ACTIVE,
                "scratch",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                sourceRepository,
                sourceRepository,
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                deleted,
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now()
        );
    }
}
