package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.infrastructure.persistence.entity.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataConversationRepository extends JpaRepository<ConversationEntity, Long> {

    List<ConversationEntity> findByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId, Long projectId);

    List<ConversationEntity> findByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId);

    Optional<ConversationEntity> findByIdAndUserIdAndDeletedFalse(Long conversationId, Long userId);

    Optional<ConversationEntity> findByIdAndUserId(Long conversationId, Long userId);

    // #340 5-9: 만료된 휴지통 대화를 한 문장으로 지운다. 예전에는 엔티티를 전부 로드한 뒤
    // deleteById 를 N 번 불렀다 — 지우려고 읽고, 지우려고 또 한 번씩 왕복했다. 파생
    // deleteBy... 메서드도 내부적으로 같은 짓을 하므로 명시적 벌크 DELETE 로 적는다.
    // 여기서 지우는 대상은 이미 소프트 삭제돼 사용자 화면에서 사라진 대화뿐이다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ConversationEntity conversation
             where conversation.deleted = true
               and conversation.deletedAt <= :cutoff
            """)
    int deleteExpiredTrash(@Param("cutoff") LocalDateTime cutoff);

    /**
     * U6(#341) 6-8: 프로젝트의 활성 대화를 한 문장으로 휴지통에 넣는다. 예전에는 N 건을 엔티티로
     * 읽어 한 건씩 save 했다.
     *
     * <p>{@code updated_at} 은 그대로 갱신된다 — {@code chat_sessions.updated_at} 컬럼이
     * {@code ON UPDATE CURRENT_TIMESTAMP} 라 벌크 UPDATE 에서도 DB 가 채운다(@UpdateTimestamp 는
     * 벌크 문장에서 돌지 않는다). 휴지통 목록이 updated_at 순이므로 이게 유지돼야 순서가 같다.</p>
     *
     * <p>{@code clearAutomatically} 를 켜지 않는다. 영속성 컨텍스트를 비우면 같은 트랜잭션의
     * {@code ProjectRepositoryAdapter#save} 가 L1 캐시 히트에 기대는 낙관적 잠금 경로(그쪽 javadoc
     * 의 Case A)를 잃는다 — 이 메서드는 대화를 읽지 않으므로 비울 이유도 없다.</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConversationEntity c
               set c.deleted = true,
                   c.deletedAt = :deletedAt
             where c.userId = :userId
               and c.projectId = :projectId
               and c.deleted = false
            """)
    int softDeleteByUserIdAndProjectId(
            @Param("userId") Long userId,
            @Param("projectId") Long projectId,
            @Param("deletedAt") LocalDateTime deletedAt
    );

    /**
     * U6 6-8: 프로젝트의 대화를 한 문장으로 지운다. 메시지는 DB 가 지운다 — chat_messages 의 FK 가
     * V19 부터 ON DELETE CASCADE 다(#338). 실제 SQL DELETE 이므로 벌크여도 CASCADE·SET NULL 이
     * 그대로 돈다(approvals·agent_runs 등은 SET NULL 로 이력을 보존한다).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ConversationEntity c where c.userId = :userId and c.projectId = :projectId")
    int deleteByUserIdAndProjectId(@Param("userId") Long userId, @Param("projectId") Long projectId);

    // 보관 기간이 지난 휴지통 대화 삭제는 위 deleteExpiredTrash 하나로 충분하다(#340 에서 먼저 들어왔다).
}
