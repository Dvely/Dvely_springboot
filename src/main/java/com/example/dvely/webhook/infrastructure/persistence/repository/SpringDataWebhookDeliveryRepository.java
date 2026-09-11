package com.example.dvely.webhook.infrastructure.persistence.repository;

import com.example.dvely.webhook.infrastructure.persistence.entity.WebhookDeliveryEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataWebhookDeliveryRepository
        extends JpaRepository<WebhookDeliveryEntity, String> {

    @Query("""
            select delivery.id
            from WebhookDeliveryEntity delivery
            where delivery.status in :statuses
              and delivery.nextAttemptAt <= :now
            order by delivery.receivedAt asc
            """)
    List<String> findRunnableIds(
            @Param("statuses") Collection<String> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WebhookDeliveryEntity delivery
               set delivery.status = :processingStatus,
                   delivery.attempt = delivery.attempt + 1,
                   delivery.leaseOwner = :workerId,
                   delivery.leaseUntil = :leaseUntil,
                   delivery.nextAttemptAt = null
             where delivery.id = :deliveryId
               and delivery.status in :claimableStatuses
            """)
    int claim(
            @Param("deliveryId") String deliveryId,
            @Param("workerId") String workerId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("claimableStatuses") Collection<String> claimableStatuses,
            @Param("processingStatus") String processingStatus
    );

    List<WebhookDeliveryEntity> findByStatusAndLeaseUntilBefore(String status, LocalDateTime now);

    /**
     * 보존 스윕(#338). JPQL 벌크 삭제는 LIMIT 을 표현할 수 없어 네이티브로 쓴다 —
     * {@code SpringDataAuditLogRepository#deleteBatch} 와 같은 이유·같은 형태다.
     *
     * <p>{@code ORDER BY} 를 일부러 붙이지 않았다. status 값이 여러 개라
     * {@code idx_webhook_deliveries_retention (status, received_at)} 위에서 received_at 으로
     * 정렬하려면 filesort 가 붙는다(EXPLAIN 으로 확인). 배치를 끊는 데 순서는 필요 없다 —
     * 조건에 맞는 아무 batchSize 건이면 되고, 호출자가 0 이 될 때까지 반복한다.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from webhook_deliveries"
            + " where status in (:statuses) and received_at < :cutoff limit :batchSize",
            nativeQuery = true)
    int deleteTerminalBatch(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );
}
