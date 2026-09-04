package com.example.dvely.chat.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
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
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private AsyncDecisionRunner asyncDecisionRunner;

    @Mock
    private AiProperties aiProperties;

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
    void sendMessageOpensPendingTaskAndDispatchesDecisionAsynchronously() {
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
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 2L))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
        when(aiProperties.getDefaultProvider()).thenReturn(AiProvider.ANTHROPIC);
        when(agentOrchestrator.createPending(2L, 21L)).thenReturn("task-abc123");

        MessageResult result = chatCommandService.sendMessage(2L, 21L, "FAQ를 추가해줘", null);

        // taskId 는 Decision 을 기다리지 않고 즉시 발급된 PENDING 태스크의 id 다.
        assertThat(result.messageId()).isEqualTo(31L);
        assertThat(result.taskId()).isEqualTo("task-abc123");
        assertThat(conversation.getTitle()).isEqualTo("FAQ를 추가해줘");
        verify(conversationRepository).save(conversation);
        // Decision→제출은 백그라운드로 넘어간다(제공자 미지정 → 기본 제공자, projectId 는 대화의 값).
        verify(agentOrchestrator).createPending(2L, 21L);
        verify(asyncDecisionRunner).decideAndSubmit("task-abc123", 2L, 21L, 7L, AiProvider.ANTHROPIC);
    }

    @Test
    void sendMessagePassesRequestedAiProviderToTheAsyncDecision() {
        Conversation conversation = new Conversation(
                21L, 2L, 7L, false, null, LocalDateTime.now(), LocalDateTime.now());
        ChatMessage saved = new ChatMessage(
                31L, 21L, com.example.dvely.chat.domain.value.ChatRole.USER, "FAQ를 추가해줘", 0, LocalDateTime.now());
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 2L))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
        when(agentOrchestrator.createPending(2L, 21L)).thenReturn("task-glm");

        MessageResult result = chatCommandService.sendMessage(2L, 21L, "FAQ를 추가해줘", AiProvider.GLM);

        assertThat(result.taskId()).isEqualTo("task-glm");
        // 제공자를 지정하면 기본값 해석 없이 그대로 백그라운드 Decision 에 전달된다.
        verify(asyncDecisionRunner).decideAndSubmit("task-glm", 2L, 21L, 7L, AiProvider.GLM);
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
