package com.example.dvely.chat.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.chat.application.result.ConversationResult;
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
