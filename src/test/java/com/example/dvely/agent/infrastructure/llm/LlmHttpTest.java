package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
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
}
