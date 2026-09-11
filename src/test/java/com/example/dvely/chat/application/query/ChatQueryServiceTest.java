package com.example.dvely.chat.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(projectRepository.findAllByIdInAndOwnerUserId(List.of(7L), 2L))
                .thenReturn(List.of(project));

        var results = service.getTrashConversations(2L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.title()).isEqualTo("FAQ 섹션 추가");
            assertThat(result.projectName()).isEqualTo("qeploy-landing");
            assertThat(result.retentionExpiresAt()).isEqualTo(deletedAt.plusDays(7));
            assertThat(result.remainingRetentionDays()).isEqualTo(6);
        });
    }

    /**
     * U6(#341) 6-7: 휴지통 목록이 대화 수에 비례해 프로젝트를 조회하지 않는다. 대화 3건이 서로 다른
     * 프로젝트를 가리켜도 프로젝트 조회는 배치 2번(id 묶음 · 대체 저장소 묶음)이 전부다 — 예전에는
     * 대화마다 최대 3번, 즉 최대 9번이었다.
     */
    @Test
    void trashProjectLookupsDoNotGrowWithConversationCount() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(1);
        when(conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(2L))
                .thenReturn(List.of(
                        trashConversation(11L, 7L, deletedAt),
                        trashConversation(12L, 8L, deletedAt),
                        trashConversation(13L, 9L, deletedAt)));
        when(projectRepository.findAllByIdInAndOwnerUserId(List.of(7L, 8L, 9L), 2L))
                .thenReturn(List.of(
                        project(7L, 2L, "alpha"), project(8L, 2L, "beta"), project(9L, 2L, "gamma")));

        assertThat(service.getTrashConversations(2L)).hasSize(3);

        verify(projectRepository, times(1)).findAllByIdInAndOwnerUserId(List.of(7L, 8L, 9L), 2L);
        // 살아 있는 프로젝트만이라 대체 저장소 조회는 아예 돌지 않는다.
        verify(projectRepository, never()).findAllActiveByOwnerUserIdAndSourceRepositoryIn(any(), any());
        verify(projectRepository, never()).findByIdAndOwnerUserIdAndDeletedFalse(any(), any());
        verify(projectRepository, never()).findByIdAndOwnerUserId(any(), any());
    }

    /**
     * 원본 프로젝트가 삭제됐으면 같은 저장소를 쓰는 활성 프로젝트로 보정한다 — 예전 대화별 로직과
     * 같은 판정이고, 그 조회가 배치 1번으로 합쳐졌다는 것만 다르다.
     */
    @Test
    void trashConversationFallsBackToActiveProjectWithTheSameRepository() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(1);
        when(conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(2L))
                .thenReturn(List.of(trashConversation(11L, 7L, deletedAt)));
        when(projectRepository.findAllByIdInAndOwnerUserId(List.of(7L), 2L))
                .thenReturn(List.of(deletedProject(7L, 2L, "예전 프로젝트", "Otter/Sample-Repo")));
        when(projectRepository.findAllActiveByOwnerUserIdAndSourceRepositoryIn(2L, List.of("Otter/Sample-Repo")))
                .thenReturn(List.of(project(9L, 2L, "새 프로젝트")));

        var results = service.getTrashConversations(2L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.projectId()).isEqualTo(9L);
            assertThat(result.projectName()).isEqualTo("새 프로젝트");
        });
    }

    /** 원본도 없고 같은 저장소의 활성 프로젝트도 없으면 예전처럼 "삭제된 프로젝트" 로 표시한다. */
    @Test
    void trashConversationShowsDeletedPlaceholderWhenNoProjectRemains() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(1);
        when(conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(2L))
                .thenReturn(List.of(trashConversation(11L, 7L, deletedAt)));
        when(projectRepository.findAllByIdInAndOwnerUserId(List.of(7L), 2L)).thenReturn(List.of());

        var results = service.getTrashConversations(2L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.projectId()).isEqualTo(7L);
            assertThat(result.projectName()).isEqualTo("삭제된 프로젝트");
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

    private Conversation trashConversation(Long conversationId, Long projectId, LocalDateTime deletedAt) {
        return new Conversation(
                conversationId, 2L, projectId, "휴지통 대화",
                true, deletedAt, deletedAt.minusDays(2), deletedAt
        );
    }

    private Project deletedProject(Long projectId, Long ownerUserId, String name, String sourceRepository) {
        return new Project(
                projectId, ownerUserId, name, ProjectStatus.ARCHIVED, "scratch", null, "fast",
                DeployStatus.DRAFT, null, null, sourceRepository, sourceRepository,
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.BOUND, RepositoryHealthStatus.HEALTHY,
                true, LocalDateTime.now().minusDays(3), LocalDateTime.now()
        );
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
