package com.example.dvely.chat.application.command;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ChatCommandService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final AgentOrchestrator agentOrchestrator;
    private final AsyncDecisionRunner asyncDecisionRunner;
    private final AiProperties aiProperties;

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

    /**
     * U6(#341) 6-8: 한 문장으로 옮긴다. 예전에는 대화 N 건을 엔티티로 읽어 한 건씩 save 했다 —
     * 프로젝트 삭제 한 번에 SELECT 1 + UPDATE N 이었다. softDelete 의 "이미 삭제됐으면 무시" 가드는
     * 쿼리의 {@code deleted = false} 조건이 그대로 대신한다.
     */
    @Transactional
    public void trashConversationsForProject(Long userId, Long projectId) {
        conversationRepository.softDeleteAllByUserIdAndProjectId(userId, projectId, LocalDateTime.now());
    }

    /**
     * U6 6-8: 한 문장으로 지운다. 메시지는 따로 지우지 않는다(#338) — chat_messages 의 FK 가 V19
     * 부터 ON DELETE CASCADE 라 DB 가 함께 지운다. 벌크 DELETE 도 실제 SQL DELETE 이므로 그 CASCADE
     * 와 다른 테이블의 SET NULL(approvals·agent_runs 등 이력 보존)이 예전과 똑같이 돈다.
     */
    @Transactional
    public void deleteConversationsForProject(Long userId, Long projectId) {
        conversationRepository.deleteAllByUserIdAndProjectId(userId, projectId);
    }

    /**
     * 만료된 휴지통 대화를 영구 삭제한다.
     *
     * <p>#340 5-9 · #341 6-8: 엔티티를 전부 로드한 뒤 {@code deleteById} 를 N 번 부르던 것을 벌크
     * DELETE 한 문장으로 바꿨다. 지우려고 읽을 이유가 없다 — 삭제 조건이 곧 SELECT 조건이었다.</p>
     */
    @Transactional
    public int purgeExpiredConversations() {
        return conversationRepository.deleteExpiredTrash(ChatTrashPolicy.cutoff(LocalDateTime.now()));
    }

    @Transactional
    public MessageResult sendMessage(Long userId, Long conversationId, String content, AiProvider requestedProvider) {
        Conversation conversation = conversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId, userId));
        AiProvider provider = requestedProvider != null ? requestedProvider : aiProperties.getDefaultProvider();

        // Decision(LLM 호출)은 오래 걸린다. 요청 스레드에서 기다리면 FE 가 타임아웃(Network Error)
        // 나므로, PENDING 태스크를 열어 taskId 만 먼저 응답하고 Decision→제출은 백그라운드로 넘긴다.
        // FE 는 이 taskId 로 SSE 를 열어 계획·진행·실패를 실시간으로 받는다(항상 non-null).
        //
        // 메시지 저장보다 먼저 하는 이유: 사용자 발화에도 taskId 를 실어 저장해야 목록 조회에서
        // "이 요청이 어느 작업을 낳았나" 를 알 수 있다. 예전에는 저장이 먼저라 taskId 를 못 넣었고,
        // 그 값은 이 POST 응답에만 실려 나가 GET 목록에서는 전부 null 이었다.
        // 같은 트랜잭션이므로 뒤가 실패하면 PENDING 태스크도 함께 롤백된다.
        Long projectId = conversation.getProjectId();
        String taskId = agentOrchestrator.createPending(userId, conversationId);

        ChatMessage message = chatMessageRepository.save(
                new ChatMessage(conversation.getId(), ChatRole.USER, content, 0, null, taskId)
        );
        if (conversation.assignTitleFromFirstMessage(content)) {
            conversationRepository.save(conversation);
        }

        dispatchDecisionAfterCommit(taskId, userId, conversationId, projectId, provider);
        return toMessageResult(message, taskId);
    }

    /**
     * PENDING 태스크가 커밋된 뒤에만 비동기 Decision 을 띄운다. @Async 로 넘긴 일은 이 트랜잭션이
     * 커밋되기 전에도 다른 스레드에서 시작될 수 있는데, 그 스레드의 새 트랜잭션은 아직 커밋 안 된
     * PENDING 행을 못 봐 {@code TaskStore.savePlan}/{@code enqueue} 가 태스크를 못 찾는다. 그래서
     * afterCommit 콜백으로 커밋 이후로 미룬다. 트랜잭션 동기화가 없는 문맥(단위 테스트 등)에서는
     * 곧바로 실행한다.
     *
     * <p>executor 포화로 디스패치가 거부되면(TaskRejectedException) 그 태스크를 FAILED 로 닫는다 —
     * {@code createPending} 이 연 PENDING 을 아무도 확정/종료하지 않고 방치하지 않기 위해서다.</p>
     */
    private void dispatchDecisionAfterCommit(String taskId,
                                             Long userId,
                                             Long conversationId,
                                             Long projectId,
                                             AiProvider provider) {
        Runnable dispatch = () -> {
            try {
                asyncDecisionRunner.decideAndSubmit(taskId, userId, conversationId, projectId, provider);
            } catch (TaskRejectedException rejected) {
                agentOrchestrator.markDecisionFailed(
                        taskId,
                        conversationId,
                        "서버가 혼잡해 요청을 시작하지 못했습니다. 잠시 후 다시 시도해주세요."
                );
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
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
                taskId,
                message.getKind()
        );
    }
}
