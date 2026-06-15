package com.example.dvely.webhook.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.webhook.application.WebhookService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookControllerTest {

    @Test
    void receivesVerifiedDeliveryAsAcceptedQueueSubmission() {
        WebhookService webhookService = org.mockito.Mockito.mock(WebhookService.class);
        WebhookController controller = new WebhookController(webhookService);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(webhookService.receive("delivery-1", "push", payload)).thenReturn(true);

        var response = controller.receiveGithubWebhook(
                "push",
                "delivery-1",
                "sha256=signature",
                payload
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(webhookService).verifySignature(payload, "sha256=signature");
        verify(webhookService).receive("delivery-1", "push", payload);
    }

    @Test
    void invalidSignatureDoesNotEnqueueDelivery() {
        WebhookService webhookService = org.mockito.Mockito.mock(WebhookService.class);
        WebhookController controller = new WebhookController(webhookService);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid signature"))
                .when(webhookService)
                .verifySignature(payload, "sha256=invalid");

        assertThatThrownBy(() -> controller.receiveGithubWebhook(
                "push",
                "delivery-1",
                "sha256=invalid",
                payload
        )).isInstanceOf(IllegalArgumentException.class);

        verify(webhookService, never()).receive("delivery-1", "push", payload);
    }
}
