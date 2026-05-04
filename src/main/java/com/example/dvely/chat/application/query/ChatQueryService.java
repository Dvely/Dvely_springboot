package com.example.dvely.chat.application.query;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.exception.ConversationNotFoundException;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
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

    private static final int TRASH_RETENTION_DAYS = 30;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;

    public List<ConversationResult> getConversations(Long userId, Long projectId) {
        assertProjectAccessible(userId, projectId);
        return conversationRepository.findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public ConversationResult getConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        return toResult(conversation);
    }

    public List<ConversationResult> getTrashConversations(Long userId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TRASH_RETENTION_DAYS);
        return conversationRepository.findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(userId)
            .stream()
            .filter(conversation -> isWithinRetention(conversation, cutoff))
            .map(conversation -> toResult(conversation, resolveTrashProjectId(userId, conversation)))
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

    private void assertProjectAccessible(Long userId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found. projectId=" + projectId + ", ownerUserId=" + userId));
    }

    private ConversationResult toResult(Conversation conversation) {
        return toResult(conversation, conversation.getProjectId());
    }

    private ConversationResult toResult(Conversation conversation, Long projectId) {
        return new ConversationResult(
                conversation.getId(),
                projectId,
                conversation.isDeleted(),
                conversation.getDeletedAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private MessageResult toMessageResult(ChatMessage message) {
        return new MessageResult(
                message.getId(),
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt()
        );
    }

    private boolean isWithinRetention(Conversation conversation, LocalDateTime cutoff) {
        if (!conversation.isDeleted()) {
            return false;
        }
        LocalDateTime deletedAt = conversation.getDeletedAt();
        if (deletedAt == null) {
            return true;
        }
        return !deletedAt.isBefore(cutoff);
    }

    private Long resolveTrashProjectId(Long userId, Conversation conversation) {
        Long projectId = conversation.getProjectId();
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .map(Project::getId)
                .orElseGet(() -> findReplacementProjectId(userId, projectId).orElse(projectId));
    }

    private Optional<Long> findReplacementProjectId(Long userId, Long deletedProjectId) {
        return projectRepository.findByIdAndOwnerUserId(deletedProjectId, userId)
                .map(Project::getSourceRepository)
                .filter(sourceRepository -> sourceRepository != null && !sourceRepository.isBlank())
                .flatMap(sourceRepository -> projectRepository
                        .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, sourceRepository)
                        .map(Project::getId));
    }
}
