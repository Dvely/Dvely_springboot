package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * created_at 이 아니라 message_id(AUTO_INCREMENT)로 정렬한다 — 성능이 아니라 정확성 문제다(#338).
     *
     * <p>{@code chat_messages.created_at} 은 {@code DATETIME(0)}, 즉 초 단위다. 같은 초에 들어온
     * 메시지들은 값이 전부 같아 정렬이 <b>비결정적</b>이었다 — 사용자 질문과 어시스턴트 응답이
     * 뒤집혀 보이는 것이 가능했고, 같은 대화를 두 번 열면 순서가 달라질 수도 있었다.</p>
     *
     * <p>message_id 는 INSERT 순서와 단조 일치하므로 "쓴 순서"를 정확히 재현한다. 덤으로
     * InnoDB 보조 인덱스는 PK 를 뒤에 달고 있어 filesort 도 사라진다.</p>
     */
    List<ChatMessageEntity> findByConversationIdOrderByIdAsc(Long conversationId);
}
