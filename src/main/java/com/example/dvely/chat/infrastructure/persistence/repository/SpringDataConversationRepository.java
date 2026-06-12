package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataConversationRepository extends JpaRepository<ConversationEntity, Long> {

    List<ConversationEntity> findByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId, Long projectId);

    List<ConversationEntity> findByUserIdAndProjectId(Long userId, Long projectId);

    List<ConversationEntity> findByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId);

    List<ConversationEntity> findByDeletedTrueAndDeletedAtLessThanEqual(LocalDateTime cutoff);

    Optional<ConversationEntity> findByIdAndUserIdAndDeletedFalse(Long conversationId, Long userId);

    Optional<ConversationEntity> findByIdAndUserId(Long conversationId, Long userId);
}
