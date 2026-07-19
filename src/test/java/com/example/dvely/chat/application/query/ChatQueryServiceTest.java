package com.example.dvely.chat.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.chat.domain.value.ChatRole;
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
class ChatQueryServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ChatQueryService service;

    @Test
    void trashResponseIncludesTitleProjectNameAndRemainingRetentionDays() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(1);
        Conversation conversation = new Conversation(
                11L,
                2L,
                7L,
                "FAQ 섹션 추가",
                true,
                deletedAt,
                deletedAt.minusDays(2),
                deletedAt
        );
        Project project = project(7L, 2L, "qeploy-landing");
        when(conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(2L))
                .thenReturn(List.of(conversation));
        when(projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(7L, 2L))
                .thenReturn(Optional.of(project));

        var results = service.getTrashConversations(2L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.title()).isEqualTo("FAQ 섹션 추가");
            assertThat(result.projectName()).isEqualTo("qeploy-landing");
            assertThat(result.retentionExpiresAt()).isEqualTo(deletedAt.plusDays(7));
            assertThat(result.remainingRetentionDays()).isEqualTo(6);
        });
    }

    @Test
    void getMessagesLeavesTaskIdNullSinceHistoricalMessagesHaveNoTaskCorrelation() {
        Conversation conversation = new Conversation(
                21L,
                2L,
                7L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ChatMessage message = new ChatMessage(31L, 21L, ChatRole.ASSISTANT, "승인 정책에 따라 작업을 시작합니다.", 0, LocalDateTime.now());
        when(conversationRepository.findByIdAndUserIdAndDeletedFalse(21L, 2L))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(21L))
                .thenReturn(List.of(message));

        var results = service.getMessages(2L, 21L);

        // Only the fresh MessageResult returned by ChatCommandService.sendMessage() carries a
        // taskId (see ChatCommandServiceTest); a re-fetch of the same conversation's history
        // must not fabricate one since ChatMessage does not persist a task correlation.
        assertThat(results).singleElement().satisfies(result -> assertThat(result.taskId()).isNull());
    }

    @Test
    void trashResponseExcludesConversationAfterSevenDays() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(7).minusMinutes(1);
        Conversation expired = new Conversation(
                11L,
                2L,
                7L,
                "expired",
                true,
                deletedAt,
                deletedAt.minusDays(1),
                deletedAt
        );
        when(conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(2L))
                .thenReturn(List.of(expired));

        assertThat(service.getTrashConversations(2L)).isEmpty();
    }

    private Project project(Long projectId, Long ownerUserId, String name) {
        return new Project(
                projectId,
                ownerUserId,
                name,
                ProjectStatus.ACTIVE,
                "scratch",
                null,
                "fast",
                DeployStatus.DRAFT,
                null,
                null,
                "otter/sample-repo",
                "otter/sample-repo",
                RepositoryVisibility.PUBLIC,
                RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY,
                false,
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now()
        );
    }
}
