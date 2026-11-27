package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 진짜 Codex CLI(이미지에 고정한 판)를 진짜 러너·어댑터로 돌려, {@code exec --json} 줄이 한 일과 답으로 읽히는지 본다(#600 PR-2).
 *
 * <p>키가 없어도 돈다. 모델 자리에 이 테스트가 띄운 가짜 Responses API 를 붙이고({@code -c model_provider=…}),
 * 도구 호출을 정해 둔다 — 명령 하나, 패치 하나(고치기·만들기), 맺음말. 그래서 CLI 판을 올릴 때 키 없이도 줄 모양이
 * 바뀌었는지 바로 안다. 키로 실제 모델을 부르는 확인은 {@link CodexCliAdapterIntegrationTest} 가 한다.</p>
 *
 * <p>가짜 서버는 이 컴퓨터에 있으므로 egress 격리는 끈다(격리는 허용 호스트만 내보낸다). 컨테이너가
 * {@code host.docker.internal} 로 이 컴퓨터에 닿아야 하므로 Docker Desktop 에서 돌린다.</p>
 *
 * <pre>
 *   docker build -t qeploy/coding-agent:local docker/coding-agent
 *   ./gradlew test --no-daemon -Ddocker.it=true --tests "*CodexStreamIntegrationTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class CodexStreamIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FAKE_KEY = "sk-proj-" + "fake0000000000000000000000000000000000";

    private Path workspace;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private List<List<Map<String, Object>>> script = List.of();
    private boolean reject;

    @BeforeEach
    void setUp() throws Exception {
        // /tmp is on Docker Desktop's default shared-path list; toRealPath() resolves the macOS
        // symlink to /private/tmp, which is the form the bind mount needs.
        workspace = Files.createTempDirectory(Path.of("/tmp"), "qeploy-codex-stream-it-").toRealPath();
        Files.writeString(workspace.resolve("index.html"), "<h1>hi</h1>\n");
        // Codex refuses to run outside a git repository; Qeploy's real workspace is a clone.
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=it@qeploy.test", "-c", "user.name=it", "commit", "-qm", "init");

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/responses", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        if (workspace != null) {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void 명령과_패치가_도는_동안_한_일로_오고_마지막_말이_답이다() throws Exception {
        script = List.of(
                List.of(
                        Map.of("type", "reasoning", "id", "rs_0",
                                "summary", List.of(Map.of("type", "summary_text", "text", "**Inspecting the page**"))),
                        message("msg_0", "먼저 index.html 을 보겠습니다."),
                        exec("ctc_0", "const r = await tools.exec_command({cmd: \"cat index.html\"});\ntext(r.output);")),
                List.of(exec("ctc_1", """
                        const p = await tools.apply_patch("*** Begin Patch\\n*** Update File: index.html\\n@@\\n-<h1>hi</h1>\\n+<h1>안녕</h1>\\n*** Add File: src/styles.css\\n+h1 { color: navy; }\\n*** End Patch\\n");
                        text(JSON.stringify(p));""")),
                List.of(message("msg_2", "제목을 안녕으로 바꿨습니다.")));
        List<CodingAgentActivity> seen = Collections.synchronizedList(new ArrayList<>());

        CodingAgentResult result = adapter().run(new CodingAgentCommand(
                "index.html 제목을 바꿔줘", workspace.toString(), FAKE_KEY, Duration.ofMinutes(3), seen::add));

        assertThat(result.success())
                .withFailMessage("Codex 실행 실패 exit=%d%nstdout:%n%s%nstderr:%n%s",
                        result.exitCode(), result.output(), result.errorOutput())
                .isTrue();
        assertThat(result.output()).isEqualTo("제목을 안녕으로 바꿨습니다.");
        assertThat(seen).containsExactly(
                new CodingAgentActivity(Kind.MESSAGE, "먼저 index.html 을 보겠습니다."),
                new CodingAgentActivity(Kind.COMMAND, "cat index.html"),
                new CodingAgentActivity(Kind.EDIT, "index.html"),
                new CodingAgentActivity(Kind.EDIT, "src/styles.css"));
        assertThat(workspace.resolve("index.html")).content().contains("<h1>안녕</h1>");
    }

    @Test
    void 키가_거절되면_실패_이유가_스트림에서_온다() {
        reject = true;

        CodingAgentResult result = adapter().run(new CodingAgentCommand(
                "index.html 제목을 바꿔줘", workspace.toString(), FAKE_KEY, Duration.ofMinutes(3), activity -> { }));

        assertThat(result.success()).isFalse();
        assertThat(result.errorOutput()).startsWith("unexpected status 401 Unauthorized: Incorrect API key provided");
    }

    private CodexCliAdapter adapter() {
        CodingAgentProperties properties = new CodingAgentProperties(); // production defaults
        properties.getEgress().setEnabled(false);
        List<String> argv = new ArrayList<>(properties.getCodex().getArgvPrefix());
        argv.addAll(List.of(
                "-c", "model_provider=fake",
                "-c", "model_providers.fake={name=\"fake\",base_url=\"http://host.docker.internal:"
                        + server.getAddress().getPort() + "/v1\",wire_api=\"responses\"}"));
        properties.getCodex().setArgvPrefix(argv);
        return new CodexCliAdapter(new CodingAgentContainerRunner(properties), properties);
    }

    private void respond(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        int n = requests.getAndIncrement();
        if (reject) {
            byte[] body = "{\"error\":{\"message\":\"Incorrect API key provided: sk-proj-****0000.\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        List<Map<String, Object>> items = script.get(Math.min(n, script.size() - 1));
        String id = "resp-" + n;
        exchange.getResponseHeaders().add("content-type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            sse(out, Map.of("type", "response.created", "response", Map.of("id", id)));
            for (Map<String, Object> item : items) {
                sse(out, Map.of("type", "response.output_item.done", "item", item));
            }
            sse(out, Map.of("type", "response.completed", "response", Map.of("id", id, "usage", Map.of(
                    "input_tokens", 100, "input_tokens_details", Map.of("cached_tokens", 0),
                    "output_tokens", 10, "output_tokens_details", Map.of("reasoning_tokens", 0),
                    "total_tokens", 110))));
        }
    }

    private static void sse(OutputStream out, Map<String, Object> event) throws IOException {
        out.write(("event: " + event.get("type") + "\ndata: " + JSON.writeValueAsString(event) + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, Object> message(String id, String text) {
        return Map.of("type", "message", "role", "assistant", "id", id,
                "content", List.of(Map.of("type", "output_text", "text", text)));
    }

    /** 운영 모델에서 Codex 는 도구를 JavaScript 한 칸({@code exec})으로 묶어 부른다 */
    private static Map<String, Object> exec(String id, String source) {
        return Map.of("type", "custom_tool_call", "id", id, "call_id", "call_" + id, "name", "exec", "input", source);
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", workspace.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 실패: " + out);
        }
    }
}
