package com.example.dvely.webhook.application;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.webhook.domain.model.WebhookDelivery;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final GithubProperties githubProperties;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookEventHandler webhookEventHandler;

    public void verifySignature(byte[] payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new IllegalArgumentException("유효하지 않은 webhook 서명 형식");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    githubProperties.app().webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));

            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("webhook 서명 불일치");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("서명 검증 중 오류 발생", exception);
        }
    }

    public boolean receive(String deliveryId, String eventType, byte[] payload) {
        boolean accepted = webhookDeliveryRepository.enqueue(
                new WebhookDelivery(deliveryId, eventType, payload)
        );
        log.info("GitHub webhook 수신: deliveryId={} event={} accepted={}",
                deliveryId, eventType, accepted);
        return accepted;
    }

    public void processDelivery(String deliveryId) {
        WebhookDelivery delivery = webhookDeliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "webhook delivery를 찾을 수 없습니다. deliveryId=" + deliveryId));
        if (delivery.getStatus() != WebhookDeliveryStatus.PROCESSING) {
            log.debug("처리 상태가 아닌 webhook delivery 건너뜀: deliveryId={} status={}",
                    deliveryId, delivery.getStatus());
            return;
        }

        try {
            boolean handled = webhookEventHandler.handle(
                    delivery.getEventType(),
                    delivery.getPayload(),
                    delivery.getReceivedAt()
            );
            if (handled) {
                delivery.complete(LocalDateTime.now());
            } else {
                delivery.ignore(LocalDateTime.now());
            }
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            delivery.retry(message, LocalDateTime.now());
            log.error("GitHub webhook 처리 실패: deliveryId={} event={} attempt={}/{}",
                    delivery.getId(),
                    delivery.getEventType(),
                    delivery.getAttempt(),
                    delivery.getMaxAttempts(),
                    exception);
        }
        webhookDeliveryRepository.save(delivery);
    }
}
