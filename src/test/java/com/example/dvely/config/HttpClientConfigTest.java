package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 상한이 실제로 걸리는지를 소켓으로 확인한다.
 *
 * <p>단정만으로는 부족하다. Spring 의 요청 팩토리 선택과 {@code mutate()} 의 복사 규칙은 모두
 * 구현 세부이고, 둘 중 하나만 어긋나도 "타임아웃을 걸었다" 는 코드가 무한 대기로 되돌아간다.
 * 그래서 응답을 주지 않는 소켓을 세워 실제로 끊기는 것을 잰다. 운영값(30 초) 대신 1 초를 쓰는
 * 것은 확인 대상이 값이 아니라 <b>상한이 전달되는 경로</b>이기 때문이다.</p>
 */
class HttpClientConfigTest {

    private static final int READ_TIMEOUT_SECONDS = 1;
    /** 1 초 상한이 이 안에 끊기지 않으면 상한이 전달되지 않은 것이다. */
    private static final long ALLOWED_MILLIS = 15_000;

    private RestClient timedClient() {
        HttpClientConfig config = new HttpClientConfig();
        return config.githubRestClient(config.sharedHttpClient(5), READ_TIMEOUT_SECONDS);
    }

    @Test
    void 응답하지_않는_상대를_상한_안에서_끊는다() throws Exception {
        withSilentServer(port -> {
            long start = System.nanoTime();
            assertThatThrownBy(() -> timedClient().get()
                    .uri("http://127.0.0.1:" + port + "/")
                    .retrieve()
                    .body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(elapsedMillis(start)).isLessThan(ALLOWED_MILLIS);
        });
    }

    @Test
    void mutate로_파생한_클라이언트도_같은_상한을_물려받는다() throws Exception {
        // GitHub 클라이언트들은 사용자 토큰마다 mutate() 로 파생한다. 파생본이 요청 팩토리를
        // 물려받지 못하면 그 경로만 조용히 무한 대기로 돌아간다 — 원본만 검사해서는 안 잡힌다.
        withSilentServer(port -> {
            RestClient derived = timedClient().mutate()
                    .defaultHeader("Authorization", "Bearer token-of-some-user")
                    .build();

            long start = System.nanoTime();
            assertThatThrownBy(() -> derived.get()
                    .uri("http://127.0.0.1:" + port + "/")
                    .retrieve()
                    .body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(elapsedMillis(start)).isLessThan(ALLOWED_MILLIS);
        });
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 연결은 받아주되 한 바이트도 쓰지 않는 서버. 상한이 없으면 클라이언트는 여기서 영원히 기다린다. */
    private static void withSilentServer(PortConsumer body) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    // 응답을 주지 않은 채 호출 쪽이 끊을 때까지 붙잡고 있는다.
                    socket.getInputStream().read();
                } catch (IOException ignored) {
                    accepted.countDown();
                }
            });
            try {
                body.accept(server.getLocalPort());
            } finally {
                acceptor.join(Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface PortConsumer {
        void accept(int port) throws Exception;
    }
}
