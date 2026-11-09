package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ChatMessageEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <b>정렬은 언제나 {@code conversationId, id} 다.</b> 두 가지 이유가 겹친다.
 *
 * <p>정확성(#338): created_at 이 아니라 message_id(AUTO_INCREMENT)로 정렬한다. {@code chat_messages.created_at} 은
 * {@code DATETIME(0)}, 즉 초 단위라 같은 초에 들어온 메시지들의 순서가 <b>비결정적</b>이었다 — 사용자 질문과 어시스턴트
 * 응답이 뒤집혀 보일 수 있었고, 커서 페이지네이션에서는 행을 건너뛰거나 두 번 주는 버그가 된다. message_id 는 INSERT
 * 순서와 단조 일치한다.</p>
 *
 * <p>성능(#625): 앞에 붙은 {@code conversationId} 는 조건으로 이미 고정돼 의미는 같다. 그래도 적는 이유는 옵티마이저가
 * 인덱스를 고르게 하기 위해서다. {@code ORDER BY message_id LIMIT n} 이면 MySQL 은 그 순서를 그대로 주는 PK 를 골라
 * 앞에서부터 훑으며 이 대화의 행을 찾는다 — 대화가 쉬었다 이어지면 그 사이 다른 대화가 쓴 행을 전부 읽는다(실측: 커서
 * 다음 50건에 20,050행·4.9ms). {@code ORDER BY chat_session_id, message_id} 는 PK 로 줄 수 없는 순서라
 * {@code idx_chat_messages_session_message} 를 범위로 읽는다(같은 질의 50행·0.05ms, V73).</p>
 */
public interface SpringDataChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * U6(#341) 6-3: 쓴 순서대로, 상한과 커서를 더한 것. {@code after} 가 null 이면 처음부터다. 커서를 message_id 로
     * 잡은 이유는 위 정확성 문단과 같다.
     */
    @Query("""
            select m
            from ChatMessageEntity m
            where m.conversationId = :conversationId
              and (:after is null or m.id > :after)
            order by m.conversationId asc, m.id asc
            """)
    List<ChatMessageEntity> findPageByConversationId(
            @Param("conversationId") Long conversationId,
            @Param("after") Long after,
            Pageable pageable
    );

    /**
     * 대화의 마지막 몇 건(#625). <b>최신 것이 먼저</b> 온다 — 호출자가 뒤집는다. LLM 에 싣는 대화 창은 뒤에서부터 정해진
     * 수만큼이라, 대화 전체를 읽어 자바에서 자르던 것을 DB 에서 자른다.
     */
    @Query("""
            select m
            from ChatMessageEntity m
            where m.conversationId = :conversationId
            order by m.conversationId desc, m.id desc
            """)
    List<ChatMessageEntity> findLatestByConversationId(
            @Param("conversationId") Long conversationId,
            Pageable pageable
    );

    /** 위와 같되 한 화자의 발화만. 계획 수립 경로는 사용자 발화만 싣는다. */
    @Query("""
            select m
            from ChatMessageEntity m
            where m.conversationId = :conversationId
              and m.role = :role
            order by m.conversationId desc, m.id desc
            """)
    List<ChatMessageEntity> findLatestByConversationIdAndRole(
            @Param("conversationId") Long conversationId,
            @Param("role") String role,
            Pageable pageable
    );
}
