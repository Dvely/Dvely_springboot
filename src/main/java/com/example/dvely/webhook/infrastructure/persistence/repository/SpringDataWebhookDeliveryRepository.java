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
}
