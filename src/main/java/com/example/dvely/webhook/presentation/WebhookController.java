package com.example.dvely.webhook.presentation;

import com.example.dvely.webhook.application.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * GitHub App webhook 수신
     *
     * GitHub App 설정의 Webhook URL에 등록할 경로:
     *   POST {서버주소}/api/v1/webhook/github
     *
     * GitHub이 보내는 주요 헤더:
     *   X-GitHub-Event      : 이벤트 종류 (push, pull_request, installation, ...)
     *   X-Hub-Signature-256 : sha256={HMAC} - webhook secret으로 서명 검증
     *   X-GitHub-Delivery   : 이벤트 고유 UUID
     */
    @PostMapping("/github")
    public ResponseEntity<Void> receiveGithubWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] payload
    ) {
        webhookService.verifySignature(payload, signature);
        webhookService.handleEvent(eventType, payload);
        return ResponseEntity.ok().build();
    }
}
