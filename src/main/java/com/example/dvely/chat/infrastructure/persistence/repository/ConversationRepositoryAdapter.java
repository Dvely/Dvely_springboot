package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.domain.model.Conversation;
import com.example.dvely.chat.domain.repository.ConversationRepository;
import com.example.dvely.chat.infrastructure.persistence.entity.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConversationRepositoryAdapter implements ConversationRepository {

    private final SpringDataConversationRepository springDataConversationRepository;

    @Override
    public List<Conversation> findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId, Long projectId) {
        return springDataConversationRepository.findByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(userId, projectId).stream()
                .map(ConversationEntity::toDomain)
                .toList();
    }

    @Override
    public List<Conversation> findAllByUserIdAndProjectId(Long userId, Long projectId) {
        return springDataConversationRepository.findByUserIdAndProjectId(userId, projectId).stream()
                .map(ConversationEntity::toDomain)
                .toList();
    }

    @Override
    public List<Conversation> findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId) {
        return springDataConversationRepository.findByUserIdAndDeletedTrueOrderByUpdatedAtDesc(userId).stream()
                .map(ConversationEntity::toDomain)
                .toList();
    }

    @Override
    public List<Conversation> findAllByDeletedTrueAndDeletedAtLessThanEqual(LocalDateTime cutoff) {
        return springDataConversationRepository.findByDeletedTrueAndDeletedAtLessThanEqual(cutoff).stream()
                .map(ConversationEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Conversation> findByIdAndUserIdAndDeletedFalse(Long conversationId, Long userId) {
        return springDataConversationRepository.findByIdAndUserIdAndDeletedFalse(conversationId, userId)
                .map(ConversationEntity::toDomain);
    }

    @Override
    public Optional<Conversation> findByIdAndUserId(Long conversationId, Long userId) {
        return springDataConversationRepository.findByIdAndUserId(conversationId, userId)
                .map(ConversationEntity::toDomain);
    }

    @Override
    public void deleteById(Long conversationId) {
        springDataConversationRepository.deleteById(conversationId);
    }

    @Override
    public Conversation save(Conversation conversation) {
        ConversationEntity entity;
        if (conversation.getId() == null) {
            entity = ConversationEntity.from(conversation);
        } else {
            entity = springDataConversationRepository.findById(conversation.getId())
                    .orElseGet(() -> ConversationEntity.from(conversation));
            entity.updateFrom(conversation);
        }

        return springDataConversationRepository.save(entity).toDomain();
    }
}
