package com.example.dvely.webhook.presentation;

import static org.assertj.core.api.Assertions.assertThat;
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
}
