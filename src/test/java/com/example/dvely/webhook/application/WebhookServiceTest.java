package com.example.dvely.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import com.example.dvely.webhook.domain.model.WebhookDelivery;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import com.example.dvely.webhook.domain.value.WebhookDeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookServiceTest {

    @Test
    void verifiesGithubSha256SignatureUsingConstantTimeComparison() {
        WebhookService service = service(
                mock(WebhookDeliveryRepository.class),
                mock(WebhookEventHandler.class),
                "It's a Secret to Everybody"
        );

        service.verifySignature(
                "Hello, World!".getBytes(StandardCharsets.UTF_8),
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        );

        assertThatThrownBy(() -> service.verifySignature(
                "Hello, World!".getBytes(StandardCharsets.UTF_8),
                "sha256=invalid"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiveUsesDeliveryIdForIdempotentEnqueue() {
        WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
        WebhookService service = service(repository, mock(WebhookEventHandler.class), "secret");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(repository.enqueue(org.mockito.ArgumentMatchers.any(WebhookDelivery.class)))
                .thenReturn(true, false);

        assertThat(service.receive("delivery-1", "push", payload)).isTrue();
        assertThat(service.receive("delivery-1", "push", payload)).isFalse();

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(repository, org.mockito.Mockito.times(2)).enqueue(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WebhookDelivery::getId)
                .containsOnly("delivery-1");
    }

    @Test
    void processDeliveryCompletesHandledEvent() {
        WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        WebhookService service = service(repository, handler, "secret");
        WebhookDelivery delivery = processingDelivery(1, 5);
        when(repository.findById("delivery-1")).thenReturn(Optional.of(delivery));
        when(handler.handle("push", delivery.getPayload(), delivery.getReceivedAt())).thenReturn(true);

        service.processDelivery("delivery-1");

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.COMPLETED);
        verify(repository).save(delivery);
    }

    @Test
    void processDeliveryRecordsRetryAfterFailure() {
        WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        WebhookService service = service(repository, handler, "secret");
        WebhookDelivery delivery = processingDelivery(2, 5);
        when(repository.findById("delivery-1")).thenReturn(Optional.of(delivery));
        when(handler.handle("push", delivery.getPayload(), delivery.getReceivedAt()))
                .thenThrow(new IllegalStateException("temporary failure"));

        service.processDelivery("delivery-1");

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRY_WAIT);
        assertThat(delivery.getErrorMessage()).isEqualTo("temporary failure");
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        verify(repository).save(delivery);
    }

    @Test
    void processDeliveryMarksFailedWhenRetryBudgetIsExhausted() {
        WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        WebhookService service = service(repository, handler, "secret");
        WebhookDelivery delivery = processingDelivery(5, 5);
        when(repository.findById("delivery-1")).thenReturn(Optional.of(delivery));
        when(handler.handle("push", delivery.getPayload(), delivery.getReceivedAt()))
                .thenThrow(new IllegalStateException("permanent failure"));

        service.processDelivery("delivery-1");

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getProcessedAt()).isNotNull();
        assertThat(delivery.getErrorMessage()).isEqualTo("permanent failure");
        verify(repository).save(delivery);
    }

    @Test
    void processDeliveryMarksUnsupportedEventIgnored() {
        WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        WebhookService service = service(repository, handler, "secret");
        WebhookDelivery delivery = new WebhookDelivery(
                "delivery-1",
                "issues",
                "{}".getBytes(StandardCharsets.UTF_8),
                WebhookDeliveryStatus.PROCESSING,
                1,
                5,
                null,
                "worker-1",
                LocalDateTime.now().plusMinutes(1),
                null,
                LocalDateTime.now(),
                null,
                LocalDateTime.now()
        );
        when(repository.findById("delivery-1")).thenReturn(Optional.of(delivery));
        when(handler.handle("issues", delivery.getPayload(), delivery.getReceivedAt())).thenReturn(false);

        service.processDelivery("delivery-1");

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.IGNORED);
        verify(repository).save(delivery);
    }

    private WebhookDelivery processingDelivery(int attempt, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        return new WebhookDelivery(
                "delivery-1",
                "push",
                "{}".getBytes(StandardCharsets.UTF_8),
                WebhookDeliveryStatus.PROCESSING,
                attempt,
                maxAttempts,
                null,
                "worker-1",
                now.plusMinutes(1),
                null,
                now.minusSeconds(1),
                null,
                now
        );
    }

    private WebhookService service(WebhookDeliveryRepository repository,
                                   WebhookEventHandler handler,
                                   String webhookSecret) {
        GithubProperties properties = new GithubProperties(
                new GithubProperties.OAuthProperties(null, null, null, null),
                new GithubProperties.AppProperties(
                        "app-id",
                        "private-key",
                        "redirect",
                        webhookSecret,
                        "client-id",
                        "client-secret"
                )
        );
        return new WebhookService(properties, repository, handler);
    }
}
