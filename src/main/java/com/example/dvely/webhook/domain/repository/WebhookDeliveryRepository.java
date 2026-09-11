package com.example.dvely.webhook.domain.repository;

import com.example.dvely.webhook.domain.model.WebhookDelivery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryRepository {

    boolean enqueue(WebhookDelivery delivery);

    Optional<WebhookDelivery> findById(String deliveryId);

    WebhookDelivery save(WebhookDelivery delivery);

    List<String> claimPending(String workerId, int limit);

    void recoverExpiredLeases();

    /**
     * {@code cutoff} 이전에 도착한 <b>터미널 상태</b> 배달을 최대 {@code batchSize} 건 지우고
     * 실제로 지운 수를 돌려준다(#338 — 이 테이블에는 지금까지 삭제 로직이 하나도 없었다).
     *
     * <p>터미널만 지우는 것이 이 메서드의 핵심 계약이다. PENDING · RETRY_WAIT · PROCESSING 은
     * 아직 처리 중이거나 재시도를 기다리는 배달이라, 지우면 그 GitHub 이벤트가 그대로 유실된다
     * (webhook 은 재전송을 우리가 요청할 수 없다). 어떤 상태가 터미널인지는
     * {@code WebhookDeliveryStatus} 를 정본으로 어댑터가 정한다.</p>
     *
     * <p>한 번에 다 지우지 않고 배치로 끊는 이유는 {@code AuditLogRepository#deleteBatch} 와 같다:
     * payload 가 LONGBLOB 이라 한 문장으로 대량 삭제하면 그동안 행 잠금을 쥐고 undo 로그가 커진다.
     * 호출자는 0 이 돌아올 때까지 반복한다.</p>
     */
    int deleteTerminalBatch(LocalDateTime cutoff, int batchSize);
}
