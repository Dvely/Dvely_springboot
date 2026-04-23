package com.example.dvely.webhook.application;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final GithubProperties githubProperties;

    /**
     * GitHub 서명 검증
     * X-Hub-Signature-256: sha256={hmac} 헤더를 webhook secret으로 검증
     */
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
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("서명 검증 중 오류 발생", e);
        }
    }

    public void handleEvent(String eventType, byte[] payload) {
        log.info("GitHub webhook 수신: event={}", eventType);
        switch (eventType) {
            case "push" -> handlePush(payload);
            case "pull_request" -> handlePullRequest(payload);
            case "installation" -> handleInstallation(payload);
            default -> log.debug("처리하지 않는 webhook 이벤트: {}", eventType);
        }
    }

    private void handlePush(byte[] payload) {
        // TODO: push 이벤트 처리
    }

    private void handlePullRequest(byte[] payload) {
        // TODO: pull_request 이벤트 처리
    }

    private void handleInstallation(byte[] payload) {
        // TODO: installation 이벤트 처리
    }
}
