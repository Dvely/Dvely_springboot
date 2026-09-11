package com.example.dvely.agent.infrastructure.llm;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import com.example.dvely.agent.infrastructure.usage.LlmUsageStore;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 공용 RestClient 에 API 키가 고정되지 않는다는 것을 실제 요청으로 확인한다.
 *
 * <p>클라이언트를 호출마다 새로 만들던 것을 하나로 모으면서 생기는 위험은 성능이 아니라 격리다.
 * 키를 {@code defaultHeader} 로 박으면 그 값이 인스턴스에 남아, 다음 호출이 다른 키를 쓰려 해도
 * 먼저 박힌 키로 나간다 — 사용자별 키(BYOK)가 이 경로로 들어오는 순간 A 의 키로 B 의 요청이
 * 나가는 사고가 되고, 그 사고는 조용해서 로그에도 남지 않는다.</p>
 *
 * <p>GLM 경로로 재는 이유는 이 제공자만 엔드포인트가 설정값이라 로컬 서버로 돌릴 수 있기 때문이다.
 * Anthropic 경로도 같은 {@link LlmHttp#client()} 위에서 같은 방식(요청 헤더)으로 키를 싣는다 —
 * 그 공용 인스턴스가 키를 들고 있지 않다는 것은 {@link #같은_클라이언트로_보내도_요청마다_다른_키가_나간다}
 * 가 함께 확인한다.</p>
 */
class LlmApiKeyIsolationTest {

    private static final String CHAT_RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

    private HttpServer server;
    private final List<String> seenAuthorization = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        seenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().readAllBytes();
        byte[] body = CHAT_RESPONSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String endpointUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @Test
    void 키를_바꾸면_다음_요청은_바뀐_키로_나간다() {
        AiProperties properties = new AiProperties();
        properties.getGlm().setBaseUrl(endpointUrl());
        GlmClient client = new GlmClient(properties, new LlmUsageRecorder(mock(LlmUsageStore.class)));

        properties.getGlm().setApiKey("key-of-user-a");
        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults());

        properties.getGlm().setApiKey("key-of-user-b");
        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults());

        assertThat(seenAuthorization)
                .containsExactly("Bearer key-of-user-a", "Bearer key-of-user-b");
    }

    @Test
    void 같은_클라이언트로_보내도_요청마다_다른_키가_나간다() {
        // 공용 인스턴스가 어떤 키도 들고 있지 않다는 것을, 그 인스턴스로 직접 두 번 보내 확인한다.
        // Anthropic 클라이언트들이 쓰는 것과 같은 인스턴스다.
        assertThat(LlmHttp.client()).isSameAs(LlmHttp.client());

        send("key-of-user-a");
        send("key-of-user-b");

        assertThat(seenAuthorization)
                .containsExactly("Bearer key-of-user-a", "Bearer key-of-user-b");
    }

    private void send(String apiKey) {
        LlmHttp.client()
                .post()
                .uri(endpointUrl())
                .header("Authorization", "Bearer " + apiKey)
                .body("{}")
                .retrieve()
                .body(String.class);
    }
}
