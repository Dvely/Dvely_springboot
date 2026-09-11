package com.example.dvely.chat.application.query;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.exception.ConversationNotFoundException;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.policy.ChatTrashPolicy;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatQueryService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;

    public List<ConversationResult> getConversations(Long userId, Long projectId) {
        Project project = resolveActiveProject(userId, projectId);
        LocalDateTime now = LocalDateTime.now();
        return conversationRepository.findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId)
                .stream()
                .map(conversation -> toResult(conversation, new ProjectDisplay(project.getId(), project.getName()), now))
                .toList();
    }

    public ConversationResult getConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        Project project = resolveActiveProject(userId, conversation.getProjectId());
        return toResult(
                conversation,
                new ProjectDisplay(project.getId(), project.getName()),
                LocalDateTime.now()
        );
    }

    /**
     * U6(#341) 6-7: 표시할 프로젝트를 대화마다 개별 조회하던 N+1 을 걷어냈다. 휴지통 대화 N 건이면
     * 예전에는 프로젝트 조회가 최대 3N 번 돌았다(활성 조회 → 원본 조회 → 대체 프로젝트 조회).
     * 이제 프로젝트 id 를 모아 한 번, 대체 프로젝트가 필요한 저장소를 모아 한 번, 총 2번이다.
     */
    public List<ConversationResult> getTrashConversations(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Conversation> conversations = conversationRepository
            .findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(userId)
            .stream()
            .filter(conversation -> !ChatTrashPolicy.isExpired(conversation.getDeletedAt(), now))
            .toList();
        Map<Long, ProjectDisplay> displays = resolveTrashProjects(userId, conversations);
        return conversations.stream()
            .map(conversation -> toResult(conversation, displays.get(conversation.getProjectId()), now))
            .toList();
    }

    public List<MessageResult> getMessages(Long userId, Long conversationId) {
        conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));

        return chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)
            .stream()
            .map(this::toMessageResult)
            .toList();
    }

    private Project resolveActiveProject(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found. projectId=" + projectId + ", ownerUserId=" + userId));
    }

    private ConversationResult toResult(Conversation conversation, ProjectDisplay project, LocalDateTime now) {
        return new ConversationResult(
                conversation.getId(),
                project.projectId(),
                conversation.getTitle(),
                project.projectName(),
                conversation.isDeleted(),
                conversation.getDeletedAt(),
                ChatTrashPolicy.expiresAt(conversation.getDeletedAt()),
                conversation.isDeleted()
                        ? ChatTrashPolicy.remainingDays(conversation.getDeletedAt(), now)
                        : null,
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private MessageResult toMessageResult(ChatMessage message) {
        // V59 부터 chat_messages.task_id 가 있어 과거 조회에서도 태스크를 잇는다. 그 칼럼 이전에
        // 쌓인 줄과 태스크와 무관한 줄은 여전히 null 이다.
        return new MessageResult(
                message.getId(),
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt(),
                message.getTaskId(),
                message.getKind()
        );
    }

    /**
     * 대화들이 가리키는 프로젝트를 한꺼번에 풀어 projectId → 표시값 으로 만든다. 판정 순서는
     * 예전 대화별 로직과 같다: 원본이 살아 있으면 그것, 삭제됐으면 같은 저장소의 활성 프로젝트,
     * 그것도 없으면 삭제된 원본, 아예 없으면 "삭제된 프로젝트".
     */
    private Map<Long, ProjectDisplay> resolveTrashProjects(Long userId, List<Conversation> conversations) {
        List<Long> projectIds = conversations.stream()
                .map(Conversation::getProjectId)
                .distinct()
                .toList();
        if (projectIds.isEmpty()) {
            return Map.of();
        }

        // ① 해당 프로젝트들 — 삭제 여부를 가리지 않는다. 예전의 "활성 조회 → 원본 조회" 두 번을
        //    한 번으로 합친다(isDeleted 로 갈라 쓴다).
        Map<Long, Project> byId = new HashMap<>();
        for (Project project : projectRepository.findAllByIdInAndOwnerUserId(projectIds, userId)) {
            byId.put(project.getId(), project);
        }

        // ② 대체 프로젝트가 필요한 저장소들 — 원본이 삭제됐고 저장소를 갖고 있는 경우만.
        List<String> repositories = projectIds.stream()
                .map(byId::get)
                .filter(project -> project != null && project.isDeleted())
                .map(Project::getSourceRepository)
                .filter(repository -> repository != null && !repository.isBlank())
                .distinct()
                .toList();
        // 보정이 필요한 저장소가 없으면 두 번째 조회는 아예 돌지 않는다 — 휴지통 대화의 원본
        // 프로젝트가 전부 살아 있는 흔한 경우가 여기다.
        Map<String, Project> replacementByRepository = new HashMap<>();
        for (Project candidate : repositories.isEmpty()
                ? List.<Project>of()
                : projectRepository.findAllActiveByOwnerUserIdAndSourceRepositoryIn(userId, repositories)) {
            // 최신순으로 들어오므로 저장소별 첫 건만 남긴다 — 예전 findFirst...OrderByUpdatedAtDesc
            // 가 돌려주던 것과 같다. 키를 소문자로 맞추는 것은 컬럼 컬레이션이 대소문자를 구분하지
            // 않아 DB 가 대소문자 다른 값끼리 매칭해 주기 때문이다.
            replacementByRepository.putIfAbsent(normalizeRepository(candidate.getSourceRepository()), candidate);
        }

        Map<Long, ProjectDisplay> displays = new HashMap<>();
        for (Long projectId : projectIds) {
            displays.put(projectId, resolveDisplay(projectId, byId.get(projectId), replacementByRepository));
        }
        return displays;
    }

    private ProjectDisplay resolveDisplay(Long projectId,
                                          Project project,
                                          Map<String, Project> replacementByRepository) {
        if (project == null) {
            return new ProjectDisplay(projectId, "삭제된 프로젝트");
        }
        if (!project.isDeleted()) {
            return new ProjectDisplay(project.getId(), project.getName());
        }
        String repository = project.getSourceRepository();
        Project replacement = repository == null || repository.isBlank()
                ? null
                : replacementByRepository.get(normalizeRepository(repository));
        Project displayProject = replacement == null ? project : replacement;
        return new ProjectDisplay(displayProject.getId(), displayProject.getName());
    }

    private static String normalizeRepository(String repository) {
        return repository.toLowerCase(Locale.ROOT);
    }

    private record ProjectDisplay(Long projectId, String projectName) {
    }
}
