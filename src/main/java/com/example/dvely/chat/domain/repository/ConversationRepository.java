package com.example.dvely.chat.domain.repository;

import com.example.dvely.chat.domain.model.Conversation;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    List<Conversation> findAllByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId, Long projectId);

    List<Conversation> findAllByUserIdAndProjectId(Long userId, Long projectId);

    List<Conversation> findAllByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId);

    Optional<Conversation> findByIdAndUserIdAndDeletedFalse(Long conversationId, Long userId);

    Optional<Conversation> findByIdAndUserId(Long conversationId, Long userId);

    void deleteById(Long conversationId);

    Conversation save(Conversation conversation);
}
