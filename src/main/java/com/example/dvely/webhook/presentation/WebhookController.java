package com.example.dvely.webhook.presentation;

import com.example.dvely.common.response.RawApiResponse;
import com.example.dvely.webhook.application.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Webhook", description = "GitHub App webhook 수신 API. GitHub이 직접 호출하며 프론트엔드나 클라이언트에서 호출하지 않습니다.")
@RestController
@RawApiResponse
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @Operation(
            summary = "GitHub webhook 이벤트 수신",
            description = "GitHub App 설정의 Webhook URL에 등록된 엔드포인트입니다. " +
                          "push, pull_request, installation 등의 이벤트를 수신하며, " +
                          "X-Hub-Signature-256 헤더로 HMAC 서명을 검증한 뒤 이벤트를 처리합니다. " +
                          "직접 호출하는 API가 아니라 GitHub이 호출합니다."
    )
    @PostMapping("/github")
    public ResponseEntity<Void> receiveGithubWebhook(
            @Parameter(description = "이벤트 종류 (push, pull_request, installation 등)") @RequestHeader("X-GitHub-Event") String eventType,
            @Parameter(description = "GitHub webhook delivery 고유 GUID") @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @Parameter(description = "sha256={HMAC} 형식의 서명값. webhook secret으로 페이로드 무결성 검증에 사용") @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] payload
    ) {
        webhookService.verifySignature(payload, signature);
        webhookService.receive(deliveryId, eventType, payload);
        return ResponseEntity.accepted().build();
    }
}
