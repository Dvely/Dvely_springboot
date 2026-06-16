package com.example.dvely.webhook.infrastructure.persistence.repository;

import com.example.dvely.webhook.domain.model.WebhookDelivery;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import com.example.dvely.webhook.infrastructure.persistence.entity.WebhookDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WebhookDeliveryRepositoryAdapter implements WebhookDeliveryRepository {

    private static final List<String> CLAIMABLE_STATUSES = List.of(
            WebhookDeliveryStatus.PENDING.name(),
            WebhookDeliveryStatus.RETRY_WAIT.name()
    );

    private final SpringDataWebhookDeliveryRepository springDataRepository;

    @Override
    public boolean enqueue(WebhookDelivery delivery) {
        if (springDataRepository.existsById(delivery.getId())) {
            return false;
        }
        try {
            springDataRepository.saveAndFlush(WebhookDeliveryEntity.from(delivery));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    @Override
    public Optional<WebhookDelivery> findById(String deliveryId) {
        return springDataRepository.findById(deliveryId).map(WebhookDeliveryEntity::toDomain);
    }

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        WebhookDeliveryEntity entity = springDataRepository.findById(delivery.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "webhook delivery를 찾을 수 없습니다. deliveryId=" + delivery.getId()));
        entity.updateFrom(delivery);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    @Transactional
    public List<String> claimPending(String workerId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(2);
        return springDataRepository.findRunnableIds(
                        CLAIMABLE_STATUSES,
                        now,
                        PageRequest.of(0, limit)
                )
                .stream()
                .filter(deliveryId -> springDataRepository.claim(
                        deliveryId,
                        workerId,
                        leaseUntil,
                        CLAIMABLE_STATUSES,
                        WebhookDeliveryStatus.PROCESSING.name()
                ) == 1)
                .toList();
    }

    @Override
    @Transactional
    public void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        springDataRepository.findByStatusAndLeaseUntilBefore(
                        WebhookDeliveryStatus.PROCESSING.name(),
                        now
                )
                .forEach(entity -> {
                    WebhookDelivery delivery = entity.toDomain();
                    delivery.recoverExpiredLease(now);
                    entity.updateFrom(delivery);
                });
    }
}
