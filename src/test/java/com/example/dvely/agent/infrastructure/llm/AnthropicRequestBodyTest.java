package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmToolResponse;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.application.service.ConversationWindow;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.LlmUsage;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import com.example.dvely.agent.infrastructure.usage.LlmUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 실제로 나가는 요청 본문을 본다.
 *
 * <p>이 단위에서 바꾼 것들(캐시 브레이크포인트 위치, 대화 윈도우, {@code max_tokens})은 전부
 * <b>조용히 틀리는</b> 종류다 — 위치가 어긋나거나 윈도우가 안 걸려도 요청은 그대로 200 으로
 * 통과하고, 달라지는 것은 청구서뿐이다. 그래서 목 서버를 세워 본문을 직접 읽는다.</p>
 *
 * <p>토큰 절감 폭도 여기서 잰다. 목 응답의 토큰 수는 우리가 적은 값이라 그것으로는 아무것도
 * 증명하지 못하므로, <b>나가는 본문의 실제 바이트</b>를 센다.</p>
 */
class AnthropicRequestBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CodeAgentService 의 도구 정의와 같은 모양(개수와 순서만 같으면 배치 검증에는 충분하다). */
    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("execute_command", "Execute a shell command.",
                    Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")))),
            new ToolDefinition("write_file", "Write a file.",
                    Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))),
            new ToolDefinition("read_file", "Read a file.",
                    Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));

    private HttpServer server;
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private AiProperties aiProperties;
    private LlmUsageRecorder recorder;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = cannedResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        aiProperties = new AiProperties();
        aiProperties.getAnthropic().setApiKey("test-key-not-a-real-credential");
        aiProperties.getAnthropic().setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
        recorder = new LlmUsageRecorder(mock(LlmUsageStore.class));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ── 8-2 캐시 브레이크포인트 ────────────────────────────────────────────────

    @Test
    void sendsExactlyFourCacheBreakpointsInTheDocumentedPlaces() throws Exception {
        new ClaudeToolClient(aiProperties, recorder)
                .completeWithTools("SYSTEM", transcriptAfterRounds(3), TOOLS, AiModelOptions.defaults());

        Map<String, Object> body = lastBody();
        assertThat(countBreakpoints(body)).isEqualTo(4);
        assertThat(lastOf(list(body, "tools"))).containsKey("cache_control");
        assertThat(lastOf(list(body, "system"))).containsKey("cache_control");
        // 3 라운드 → messages 7개(요청 1 + 라운드마다 assistant/tool_result 2). user 턴은
        // 0·2·4·6 이고, 굴러가는 두 지점은 그중 마지막 둘이다.
        assertThat(markedUserTurnIndexes(body)).containsExactly(4, 6);
    }

    @Test
    void keepsTheStablePrefixByteIdenticalAsTheTranscriptGrows() throws Exception {
        // 캐싱은 접두 일치다. 접두가 라운드마다 한 바이트라도 달라지면 마커를 아무리 잘 놓아도
        // 적중률은 0 이다. tools 와 system 은 상수이므로 직렬화 결과가 같아야 한다.
        ClaudeToolClient client = new ClaudeToolClient(aiProperties, recorder);
        for (int round = 1; round <= 5; round++) {
            client.completeWithTools("SYSTEM", transcriptAfterRounds(round), TOOLS, AiModelOptions.defaults());
        }

        List<String> prefixes = new ArrayList<>();
        for (String raw : capturedBodies) {
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            prefixes.add(MAPPER.writeValueAsString(body.get("tools"))
                    + MAPPER.writeValueAsString(body.get("system"))
                    + body.get("model"));
        }

        assertThat(prefixes).containsOnly(prefixes.get(0));
    }

    @Test
    void growsOnlyByAppendingSoEachRoundsPrefixIsThePreviousRoundsRequest() throws Exception {
        // 마커가 값을 하려면 접두가 "덧붙기만" 해야 한다. 앞부분을 다시 쓰는 순간 캐시는 깨진다.
        ClaudeToolClient client = new ClaudeToolClient(aiProperties, recorder);
        for (int round = 1; round <= 4; round++) {
            client.completeWithTools("SYSTEM", transcriptAfterRounds(round), TOOLS, AiModelOptions.defaults());
        }

        for (int i = 1; i < capturedBodies.size(); i++) {
            List<Object> previous = list(MAPPER.readValue(capturedBodies.get(i - 1), Map.class), "messages");
            List<Object> current = list(MAPPER.readValue(capturedBodies.get(i), Map.class), "messages");

            assertThat(current.size()).isGreaterThan(previous.size());
            // cache_control 마커 자리만 굴러가고 내용은 그대로여야 한다.
            assertThat(stripCacheControl(current.subList(0, previous.size())))
                    .isEqualTo(stripCacheControl(previous));
        }
    }

    /**
     * 캐시가 덮는 몫을 실제 바이트로 잰다.
     *
     * <p>라운드 N+1 의 접두는 라운드 N 이 마커로 닫아 둔 지점까지다. 그 지점 이후만 새 입력이고
     * 나머지는 캐시 읽기가 되므로, 이 비율이 곧 이 변경이 건드리는 몫이다.</p>
     */
    @Test
    void measuresHowMuchOfEachRoundIsAlreadyBehindABreakpoint() throws Exception {
        ClaudeToolClient client = new ClaudeToolClient(aiProperties, recorder);
        int rounds = 10;
        for (int round = 1; round <= rounds; round++) {
            client.completeWithTools("SYSTEM", transcriptAfterRounds(round), TOOLS, AiModelOptions.defaults());
        }

        long totalSent = 0;
        long behindBreakpoint = 0;
        for (int i = 0; i < capturedBodies.size(); i++) {
            long size = capturedBodies.get(i).getBytes(StandardCharsets.UTF_8).length;
            totalSent += size;
            if (i > 0) {
                // 직전 라운드 요청 전체가 이번 라운드의 접두다(내용이 덧붙기만 하므로).
                behindBreakpoint += capturedBodies.get(i - 1).getBytes(StandardCharsets.UTF_8).length;
            }
        }

        System.out.printf("[U8 8-2] %d 라운드 전송 %,d bytes 중 브레이크포인트 뒤 접두 %,d bytes (%.1f%%)%n",
                rounds, totalSent, behindBreakpoint, 100.0 * behindBreakpoint / totalSent);

        // 라운드가 쌓일수록 새 내용보다 재전송이 압도적으로 많다 — 캐싱이 겨냥하는 것이 이 몫이다.
        assertThat(behindBreakpoint).isGreaterThan(totalSent / 2);
    }

    // ── 8-4 max_tokens ────────────────────────────────────────────────────────

    @Test
    void sendsTheToolLoopsOwnOutputBudget() throws Exception {
        new ClaudeToolClient(aiProperties, recorder)
                .completeWithTools("SYSTEM", transcriptAfterRounds(1), TOOLS, AiModelOptions.defaults());

        assertThat(lastBody().get("max_tokens")).isEqualTo(8_192);
    }

    @Test
    void sendsTheConfiguredCompletionBudgetForPlainCompletions() throws Exception {
        // 1024 였을 때 다단계 계획 JSON 이 잘렸고, 그때마다 교정 재시도가 전체 컨텍스트를 한 번 더
        // 보냈다. 설정 가능해야 운영에서 재배포 없이 올릴 수 있다.
        aiProperties.setCompletionMaxTokens(2_048);

        new ClaudeClient(aiProperties, recorder)
                .complete("SYSTEM", List.of(new LlmMessage("user", "안녕")), AiModelOptions.defaults());

        assertThat(lastBody().get("max_tokens")).isEqualTo(2_048);
    }

    // ── 8-3 대화 윈도우 ───────────────────────────────────────────────────────

    @Test
    void windowedHistorySendsDramaticallyFewerBytesThanTheWholeConversation() throws Exception {
        List<LlmMessage> whole = longConversation(120);
        List<LlmMessage> windowed = ConversationWindow.apply(whole);
        ClaudeClient client = new ClaudeClient(aiProperties, recorder);

        client.complete("SYSTEM", whole, AiModelOptions.defaults());
        long wholeBytes = capturedBodies.get(0).getBytes(StandardCharsets.UTF_8).length;

        client.complete("SYSTEM", windowed, AiModelOptions.defaults());
        long windowedBytes = capturedBodies.get(1).getBytes(StandardCharsets.UTF_8).length;

        System.out.printf("[U8 8-3] 120턴 대화 요청 본문: 전량 %,d bytes → 윈도우 %,d bytes (%.1f%% 감소)%n",
                wholeBytes, windowedBytes, 100.0 * (wholeBytes - windowedBytes) / wholeBytes);

        assertThat(windowedBytes).isLessThan(wholeBytes / 4);
        // 지금 처리할 요청(마지막 턴)은 반드시 남아 있어야 한다.
        assertThat(capturedBodies.get(1)).contains("turn-119");
    }

    // ── 8-1 사용량 집계 ───────────────────────────────────────────────────────

    @Test
    void reportsTheUsageOfEveryRoundSoTotalsCanBeCompared() throws Exception {
        ClaudeToolClient client = new ClaudeToolClient(aiProperties, recorder);
        LlmUsage total = LlmUsage.NONE;
        for (int round = 1; round <= 10; round++) {
            LlmToolResponse response = client.completeWithTools(
                    "SYSTEM", transcriptAfterRounds(round), TOOLS, AiModelOptions.defaults());
            total = total.plus(response.usage());
        }

        // 목 응답 한 건: input 1000 / output 200 / cache write 4000 / cache read 12000.
        assertThat(total.inputTokens()).isEqualTo(10_000);
        assertThat(total.outputTokens()).isEqualTo(2_000);
        assertThat(total.cacheCreationInputTokens()).isEqualTo(40_000);
        assertThat(total.cacheReadInputTokens()).isEqualTo(120_000);
        assertThat(total.totalTokens()).isEqualTo(172_000);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    /** CodeAgentService 의 Claude 루프가 N 라운드 뒤 갖게 되는 트랜스크립트와 같은 모양. */
    private static List<Map<String, Object>> transcriptAfterRounds(int rounds) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "할 일 앱을 만들어줘"));
        for (int i = 0; i < rounds; i++) {
            messages.add(Map.of("role", "assistant", "content",
                    List.of(Map.of("type", "tool_use", "id", "call-" + i, "name", "execute_command",
                            "input", Map.of("command", "ls /workspace")))));
            messages.add(Map.of("role", "user", "content",
                    List.of(Map.of("type", "tool_result", "tool_use_id", "call-" + i,
                            "content", "출력 ".repeat(200) + i))));
        }
        return messages;
    }

    private static List<LlmMessage> longConversation(int turns) {
        List<LlmMessage> history = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            history.add(new LlmMessage(
                    i % 2 == 0 ? "user" : "assistant",
                    "turn-" + i + " " + "내용 ".repeat(40)));
        }
        return List.copyOf(history);
    }

    private static String cannedResponse() {
        return """
                {"id":"msg_1","model":"claude-opus-4-5-20251101","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"완료"}],
                 "usage":{"input_tokens":1000,"output_tokens":200,
                          "cache_creation_input_tokens":4000,"cache_read_input_tokens":12000}}
                """;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastBody() throws Exception {
        return MAPPER.readValue(capturedBodies.get(capturedBodies.size() - 1), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> body, String key) {
        return (List<Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastOf(List<Object> items) {
        return (Map<String, Object>) items.get(items.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> markedUserTurnIndexes(Map<String, Object> body) {
        List<Integer> marked = new ArrayList<>();
        List<Object> messages = list(body, "messages");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> message = (Map<String, Object>) messages.get(i);
            if (message.get("content") instanceof List<?> blocks && !blocks.isEmpty()
                    && blocks.get(blocks.size() - 1) instanceof Map<?, ?> block
                    && block.containsKey("cache_control")) {
                marked.add(i);
            }
        }
        return marked;
    }

    private static int countBreakpoints(Object node) {
        if (node instanceof Map<?, ?> map) {
            int count = map.containsKey("cache_control") ? 1 : 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!"cache_control".equals(entry.getKey())) {
                    count += countBreakpoints(entry.getValue());
                }
            }
            return count;
        }
        if (node instanceof List<?> items) {
            int count = 0;
            for (Object item : items) {
                count += countBreakpoints(item);
            }
            return count;
        }
        return 0;
    }

    private static Object stripCacheControl(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (!"cache_control".equals(key)) {
                    copy.put(key, stripCacheControl(value));
                }
            });
            return copy;
        }
        if (node instanceof List<?> items) {
            return items.stream().map(AnthropicRequestBodyTest::stripCacheControl).toList();
        }
        return node;
    }
}
