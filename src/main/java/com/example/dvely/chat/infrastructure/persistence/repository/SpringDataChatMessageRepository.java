package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * U6(#341) 6-3: 위와 같은 정렬(message_id asc — 그 근거는 위 javadoc)에 상한과 커서를 더한 것.
     * {@code after} 가 null 이면 처음부터다.
     *
     * <p>커서를 message_id 로 잡은 이유도 같다: created_at 은 DATETIME(0) 이라 같은 초의 행 순서가
     * 비결정적인데, 커서 페이지네이션에서 그건 행을 건너뛰거나 두 번 주는 버그가 된다.</p>
     */
    @Query("""
            select m
            from ChatMessageEntity m
            where m.conversationId = :conversationId
              and (:after is null or m.id > :after)
            order by m.id asc
            """)
    List<ChatMessageEntity> findPageByConversationId(
            @Param("conversationId") Long conversationId,
            @Param("after") Long after,
            Pageable pageable
    );
}
