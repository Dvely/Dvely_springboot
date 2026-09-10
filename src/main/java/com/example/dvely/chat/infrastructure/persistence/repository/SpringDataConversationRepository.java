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

    List<ConversationEntity> findByUserIdAndProjectId(Long userId, Long projectId);

    List<ConversationEntity> findByUserIdAndDeletedTrueOrderByUpdatedAtDesc(Long userId);

    List<ConversationEntity> findByDeletedTrueAndDeletedAtLessThanEqual(LocalDateTime cutoff);

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
}
