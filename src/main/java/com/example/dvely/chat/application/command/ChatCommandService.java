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

    private static final int TRASH_RETENTION_DAYS = 30;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final DecisionAgentService decisionAgentService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentMessageService agentMessageService;

    @Transactional
    public ConversationResult createConversation(Long userId, Long projectId) {
        assertProjectAccessible(userId, projectId);
        Conversation conversation = new Conversation(userId, projectId);
        return toResult(conversationRepository.save(conversation));
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        conversation.softDelete();
        conversationRepository.save(conversation);
    }

    @Transactional
    public ConversationResult restoreConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        assertWithinRetention(conversation);
        Long restoreProjectId = resolveRestoreProjectId(userId, conversation.getProjectId());
        conversation.restoreToProject(restoreProjectId);
        return toResult(conversationRepository.save(conversation));
    }

    @Transactional
    public void trashConversationsForProject(Long userId, Long projectId) {
        List<Conversation> conversations = conversationRepository
                .findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId);
        for (Conversation conversation : conversations) {
            conversation.softDelete();
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
    public MessageResult sendMessage(Long userId, Long conversationId, String content) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));

        ChatMessage message = chatMessageRepository.save(
                new ChatMessage(conversation.getId(), ChatRole.USER, content, 0)
        );

        try {
            AgentPlan plan = decisionAgentService.decide(
                    agentMessageService.getConversationContext(conversationId),
                    AiProvider.ANTHROPIC,
                    conversation.getProjectId()
            );
            agentOrchestrator.submit(plan, userId, conversationId);
        } catch (RuntimeException exception) {
            agentMessageService.appendAssistant(
                    conversationId,
                    "요청을 분석하지 못했습니다: " + safeMessage(exception)
            );
        }
        return toMessageResult(message);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "알 수 없는 오류"
                : exception.getMessage();
    }

    private void assertProjectAccessible(Long userId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found. projectId=" + projectId + ", ownerUserId=" + userId));
    }

    private Long resolveRestoreProjectId(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, userId)
                .map(Project::getId)
                .orElseGet(() -> findReplacementProjectId(userId, projectId));
    }

    private Long findReplacementProjectId(Long userId, Long deletedProjectId) {
        Project deletedProject = projectRepository.findByIdAndOwnerUserId(deletedProjectId, userId)
                .orElseThrow(() -> new IllegalStateException("Conversation restore target project not found. projectId=" + deletedProjectId + ", ownerUserId=" + userId));

        String sourceRepository = deletedProject.getSourceRepository();
        if (sourceRepository == null || sourceRepository.isBlank()) {
            throw new IllegalStateException("Conversation restore target repository is unknown. projectId=" + deletedProjectId + ", ownerUserId=" + userId);
        }

        return projectRepository
                .findFirstByOwnerUserIdAndSourceRepositoryIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, sourceRepository)
                .map(Project::getId)
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
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TRASH_RETENTION_DAYS);
        if (deletedAt.isBefore(cutoff)) {
            throw new IllegalStateException("Conversation restore window expired (30 days).");
        }
    }

    private ConversationResult toResult(Conversation conversation) {
        return new ConversationResult(
                conversation.getId(),
                conversation.getProjectId(),
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
}
