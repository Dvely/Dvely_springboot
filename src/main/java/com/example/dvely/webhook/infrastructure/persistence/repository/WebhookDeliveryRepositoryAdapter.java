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

    /**
     * 보존 스윕이 지워도 되는 상태(#338). 여기 <b>없는</b> 것이 중요하다:
     * PENDING · RETRY_WAIT 는 아직 처리되지 않았고, PROCESSING 은 워커가 쥔 상태라 리스가
     * 만료되면 {@link #recoverExpiredLeases()} 가 되살린다 — 셋 중 하나라도 지우면 그 GitHub
     * 이벤트는 그대로 유실된다(우리 쪽에서 재전송을 요청할 방법이 없다).
     *
     * <p>{@code WebhookDeliveryStatus} 에 상태를 추가한다면 그것이 정말 최종 상태인지 따져서
     * 여기 넣을지 정해야 한다. 기본값은 "넣지 않는다"이다.</p>
     */
    private static final List<String> TERMINAL_STATUSES = List.of(
            WebhookDeliveryStatus.COMPLETED.name(),
            WebhookDeliveryStatus.IGNORED.name(),
            WebhookDeliveryStatus.FAILED.name()
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

    /**
     * {@code @Transactional} 이 필수다 — 호출자(WebhookDeliveryRetentionScheduler)는 주변
     * 트랜잭션 없이 도는 {@code @Scheduled} 메서드이고, Spring Data 프록시는 커스텀
     * {@code @Modifying} 쿼리에 대해서는 CRUD 메서드와 달리 트랜잭션을 열어주지 않는다
     * ({@code AuditLogRepositoryAdapter#deleteBatch} 가 같은 이유로 같은 주석을 달고 있다).
     */
    @Override
    @Transactional
    public int deleteTerminalBatch(LocalDateTime cutoff, int batchSize) {
        return springDataRepository.deleteTerminalBatch(TERMINAL_STATUSES, cutoff, batchSize);
    }
}
