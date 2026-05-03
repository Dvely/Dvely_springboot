package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    void deleteAllByConversationId(Long conversationId);
}
