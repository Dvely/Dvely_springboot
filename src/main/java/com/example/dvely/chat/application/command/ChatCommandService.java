package com.example.dvely.chat.application.command;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.application.service.AgentMessageService;
import com.example.dvely.agent.application.service.DecisionAgentService;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.domain.exception.ConversationNotFoundException;
import com.example.dvely.chat.domain.model.ChatMessage;
import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.policy.ChatTrashPolicy;
import com.example.dvely.chat.domain.repository.ChatMessageRepository;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.chat.domain.value.ChatRole;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatCommandService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final DecisionAgentService decisionAgentService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentMessageService agentMessageService;

    @Transactional
    public ConversationResult createConversation(Long userId, Long projectId) {
        Project project = resolveActiveProject(userId, projectId);
        Conversation conversation = new Conversation(userId, projectId);
        return toResult(conversationRepository.save(conversation), project, LocalDateTime.now());
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        conversation.softDelete(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    @Transactional
    public void permanentlyDeleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        if (!conversation.isDeleted()) {
            throw new IllegalStateException("휴지통의 대화만 영구 삭제할 수 있습니다.");
        }
        conversationRepository.deleteById(conversationId);
    }

    @Transactional
    public ConversationResult restoreConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        assertWithinRetention(conversation);
        Project restoreProject = resolveRestoreProject(userId, conversation.getProjectId());
        conversation.restoreToProject(restoreProject.getId());
        return toResult(conversationRepository.save(conversation), restoreProject, LocalDateTime.now());
    }

    @Transactional
    public void trashConversationsForProject(Long userId, Long projectId) {
        List<Conversation> conversations = conversationRepository
                .findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId);
        LocalDateTime deletedAt = LocalDateTime.now();
        for (Conversation conversation : conversations) {
            conversation.softDelete(deletedAt);
            conversationRepository.save(conversation);
        }
    }

    @Transactional
    public void deleteConversationsForProject(Long userId, Long projectId) {
        List<Conversation> conversations = conversationRepository.findAllByUserIdAndProjectId(userId, projectId);
        for (Conversation conversation : conversations) {
            if (conversation.getId() == null) {
                continue;
            }
            chatMessageRepository.deleteAllByConversationId(conversation.getId());
            conversationRepository.deleteById(conversation.getId());
        }
    }

    @Transactional
    public int purgeExpiredConversations() {
        List<Conversation> expired = conversationRepository.findAllByDeletedTrueAndDeletedAtLessThanEqual(
                ChatTrashPolicy.cutoff(LocalDateTime.now())
        );
        expired.stream()
                .map(Conversation::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(conversationRepository::deleteById);
        return expired.size();
    }

    @Transactional
    public MessageResult sendMessage(Long userId, Long conversationId, String content) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));

        ChatMessage message = chatMessageRepository.save(
                new ChatMessage(conversation.getId(), ChatRole.USER, content, 0)
        );
        if (conversation.assignTitleFromFirstMessage(content)) {
            conversationRepository.save(conversation);
        }

        // taskId stays null when the Decision Agent fails to classify the message (see catch
        // below) — the caller (FE) uses its presence to know whether there is anything to poll.
        String taskId = null;
        try {
            AgentPlan plan = decisionAgentService.decide(
                    agentMessageService.getConversationContext(conversationId),
                    AiProvider.ANTHROPIC,
                    conversation.getProjectId()
            );
            taskId = agentOrchestrator.submit(plan, userId, conversationId).taskId();
        } catch (RuntimeException exception) {
            agentMessageService.appendAssistant(
                    conversationId,
                    "요청을 분석하지 못했습니다: " + safeMessage(exception)
            );
        }
        return toMessageResult(message, taskId);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "알 수 없는 오류"
                : exception.getMessage();
    }

    private Project resolveActiveProject(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found. projectId=" + projectId + ", ownerUserId=" + userId));
    }

    private Project resolveRestoreProject(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseGet(() -> findReplacementProject(userId, projectId));
    }

    private Project findReplacementProject(Long userId, Long deletedProjectId) {
        Project deletedProject = projectRepository.findByIdAndOwnerUserId(deletedProjectId, userId)
                .orElseThrow(() -> new IllegalStateException("Conversation restore target project not found. projectId=" + deletedProjectId + ", ownerUserId=" + userId));

        String sourceRepository = deletedProject.getSourceRepository();
        if (sourceRepository == null || sourceRepository.isBlank()) {
            throw new IllegalStateException("Conversation restore target repository is unknown. projectId=" + deletedProjectId + ", ownerUserId=" + userId);
        }

        return projectRepository
                .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, sourceRepository)
                .orElseThrow(() -> new IllegalStateException(
                        "Conversation restore requires an active project with the same repository. repository=" + sourceRepository
                ));
    }

    private void assertWithinRetention(Conversation conversation) {
        if (!conversation.isDeleted()) {
            return;
        }
        LocalDateTime deletedAt = conversation.getDeletedAt();
        if (deletedAt == null) {
            return;
        }
        if (ChatTrashPolicy.isExpired(deletedAt, LocalDateTime.now())) {
            throw new IllegalStateException("Conversation restore window expired (7 days).");
        }
    }

    private ConversationResult toResult(Conversation conversation, Project project, LocalDateTime now) {
        return new ConversationResult(
                conversation.getId(),
                conversation.getProjectId(),
                conversation.getTitle(),
                project.getName(),
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

    private MessageResult toMessageResult(ChatMessage message, String taskId) {
        return new MessageResult(
                message.getId(),
                message.getConversationId(),
                message.getRole().toStorage(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt(),
                taskId
        );
    }
}
