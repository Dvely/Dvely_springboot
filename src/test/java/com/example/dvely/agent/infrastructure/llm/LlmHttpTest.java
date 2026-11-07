package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.common.exception.LlmProviderException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmHttpTest {

    @Test
    void restClientTimesOutInsteadOfHangingWhenServerNeverResponds() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            // 연결은 받아주되 응답은 보내지 않는 서버 — LLM provider 가 멈춘(행) 상황을 재현한다.
            Thread accepter = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // 테스트 종료 시 accept/sleep 이 깨지는 건 정상.
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            RestClient client = RestClient.builder()
                    .requestFactory(LlmHttp.factory(Duration.ofSeconds(2), Duration.ofMillis(300)))
                    .build();

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> client.get()
                    .uri("http://localhost:" + port + "/")
                    .retrieve()
                    .body(String.class))
                    .isInstanceOf(Exception.class);
            long elapsedMs = System.currentTimeMillis() - start;

            // read timeout(300ms)에 끊겨야 한다. 타임아웃이 없으면 서버가 쥐고 있는 5초를 그대로
            // 기다리므로 이 단정에서 실패한다 — 그게 우리가 막는 "행" 이다.
            assertThat(elapsedMs).isLessThan(3000);
        }
    }

    /**
     * 실제 JDK 가 던지는 읽기 타임아웃을 {@link LlmProviderErrors} 가 읽기 타임아웃으로 알아보는지 본다(#620).
     * 연결 타임아웃과 읽기 타임아웃은 같은 예외 타입이고 메시지로만 갈리므로, 목이 아니라 실제 소켓으로 확인한다.
     */
    @Test
    void aRealReadTimeoutReachesTheProviderOnceAndIsNotSentAgain() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            // 요청을 받고 응답하지 않는 서버 — 모델이 아직 생성 중인 상황이다. 받은 연결 수를 센다.
            Thread accepter = new Thread(() -> {
                List<Socket> held = new ArrayList<>();
                try {
                    while (!server.isClosed()) {
                        held.add(server.accept());
                        accepted.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 테스트 끝에 서버를 닫으면 accept 가 깨진다.
                } finally {
                    held.forEach(socket -> {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                            // 정리 실패는 무시한다.
                        }
                    });
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            RestClient client = RestClient.builder()
                    .requestFactory(LlmHttp.factory(Duration.ofSeconds(2), Duration.ofMillis(300)))
                    .build();

            assertThatThrownBy(() -> LlmProviderErrors.translate("OpenAI", noWaitRetry(3), () -> client.post()
                    .uri("http://localhost:" + port + "/v1/chat/completions")
                    .body("{}")
                    .retrieve()
                    .body(String.class)))
                    .isInstanceOf(LlmProviderException.class);

            assertThat(accepted.get()).as("요청은 한 번만 전달된다").isEqualTo(1);
        }
    }

    @Test
    void aRealRefusedConnectionIsStillRetried() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        RestClient client = RestClient.builder()
                .requestFactory(LlmHttp.factory(Duration.ofSeconds(2), Duration.ofMillis(300)))
                .build();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate("OpenAI", noWaitRetry(3), () -> {
            attempts.incrementAndGet();
            return client.post()
                    .uri("http://localhost:" + closedPort + "/v1/chat/completions")
                    .body("{}")
                    .retrieve()
                    .body(String.class);
        })).isInstanceOf(LlmProviderException.class);

        assertThat(attempts.get()).as("아무것도 보내지 못한 실패는 지금처럼 재시도한다").isEqualTo(3);
    }

    private static AiProperties.Retry noWaitRetry(int maxAttempts) {
        AiProperties.Retry retry = new AiProperties.Retry();
        retry.setMaxAttempts(maxAttempts);
        retry.setInitialDelayMs(0);
        retry.setMaxDelayMs(0);
        return retry;
    }
}
