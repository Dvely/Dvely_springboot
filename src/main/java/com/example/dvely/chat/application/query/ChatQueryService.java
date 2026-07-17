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
import java.util.List;
import java.util.Optional;
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

    public List<ConversationResult> getTrashConversations(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(userId)
            .stream()
            .filter(conversation -> !ChatTrashPolicy.isExpired(conversation.getDeletedAt(), now))
            .map(conversation -> toResult(conversation, resolveTrashProject(userId, conversation), now))
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
        // Historical reads have no 1:1 message-to-task correlation (ChatMessage does not persist
        // a taskId column), so taskId is always null here — only the just-created message
        // returned by ChatCommandService.sendMessage() carries the freshly submitted taskId.
        return new MessageResult(
                message.getId(),
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt(),
                null
        );
    }

    private ProjectDisplay resolveTrashProject(Long userId, Conversation conversation) {
        Long projectId = conversation.getProjectId();
        Optional<Project> activeProject = projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId);
        if (activeProject.isPresent()) {
            Project project = activeProject.get();
            return new ProjectDisplay(project.getId(), project.getName());
        }

        Optional<Project> originalProject = projectRepository.findByIdAndOwnerUserId(projectId, userId);
        Optional<Project> replacementProject = originalProject
                .map(Project::getSourceRepository)
                .filter(sourceRepository -> sourceRepository != null && !sourceRepository.isBlank())
                .flatMap(sourceRepository -> projectRepository
                        .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                                userId,
                                sourceRepository
                        ));
        Project displayProject = replacementProject.orElseGet(() -> originalProject.orElse(null));
        return displayProject == null
                ? new ProjectDisplay(projectId, "삭제된 프로젝트")
                : new ProjectDisplay(displayProject.getId(), displayProject.getName());
    }

    private record ProjectDisplay(Long projectId, String projectName) {
    }
}
