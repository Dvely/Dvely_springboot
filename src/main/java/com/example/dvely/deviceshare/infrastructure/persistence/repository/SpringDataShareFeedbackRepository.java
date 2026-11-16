package com.example.dvely.deviceshare.infrastructure.persistence.repository;

import com.example.dvely.deviceshare.infrastructure.persistence.entity.ShareFeedbackEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataShareFeedbackRepository extends JpaRepository<ShareFeedbackEntity, Long> {

    @Query("select f from ShareFeedbackEntity f where f.shareId = :shareId order by f.createdAt desc, f.id desc")
    List<ShareFeedbackEntity> findByShare(@Param("shareId") Long shareId, Pageable pageable);

    @Query("select f from ShareFeedbackEntity f where f.shareId = :shareId and f.status = :status "
            + "order by f.createdAt desc, f.id desc")
    List<ShareFeedbackEntity> findByShareAndStatus(@Param("shareId") Long shareId, @Param("status") String status,
                                                   Pageable pageable);

    @Query("select f from ShareFeedbackEntity f where f.status in :statuses and f.shareId in "
            + "(select l.id from DeviceShareLinkEntity l where l.userId = :userId) "
            + "order by f.updatedAt desc, f.id desc")
    List<ShareFeedbackEntity> findByUser(@Param("userId") Long userId, @Param("statuses") Collection<String> statuses,
                                         Pageable pageable);

    @Query("select count(f) from ShareFeedbackEntity f where f.shareId = :shareId and f.createdAt >= :since")
    long countByShareSince(@Param("shareId") Long shareId, @Param("since") LocalDateTime since);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShareFeedbackEntity f set f.status = :to, f.updatedAt = :now "
            + "where f.id = :id and f.status in :from")
    int moveStatus(@Param("id") Long id, @Param("from") Collection<String> from, @Param("to") String to,
                   @Param("now") LocalDateTime now);

    @Query(value = "select crop is not null and length(crop) > 0, screen is not null and length(screen) > 0 "
            + "from device_share_feedback_images where feedback_id = :id", nativeQuery = true)
    List<Object[]> imagePresence(@Param("id") Long feedbackId);

    @Modifying
    @Query(value = """
            delete f from device_share_feedback f
              join device_share_links l on l.share_id = f.share_id
             where least(coalesce(l.revoked_at, l.expires_at), l.expires_at) < :cutoff
            """, nativeQuery = true)
    int deleteForLinksEndedBefore(@Param("cutoff") LocalDateTime cutoff);
}
