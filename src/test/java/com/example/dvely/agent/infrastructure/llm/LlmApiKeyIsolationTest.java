package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.application.port.out.LlmToolPort;
import com.example.dvely.agent.application.port.out.ToolDefinition;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.agent.infrastructure.usage.LlmUsageRecorder;
import com.example.dvely.agent.infrastructure.usage.LlmUsageStore;
import com.example.dvely.aiaccount.application.service.UserAiKeyResolver;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 한 호출에 실린 API 키가 다른 호출로 새지 않는다는 것을 실제 요청으로 확인한다.
 *
 * <p>이 테스트가 지키는 것은 "A 의 요청이 B 의 키로 나가지 않는다"다. 예전에는 키가 배포 설정
 * 하나라 그 위험이 잠재적이었고, 지금은 <b>키의 출처가 사용자</b>라 현실이다 — 사용자마다 키가
 * 다르고, 클라이언트·라우터·HTTP 클라이언트는 전부 모든 사용자가 함께 쓰는 싱글턴이다. 키를
 * 어느 한 곳에라도 기억시키면(필드, {@code defaultHeader}, 캐시) 다음 사용자가 그 키로 나가고,
 * 그 사고는 조용해서 로그에도 남지 않는다. 과금은 남의 계정으로 간다.</p>
 *
 * <p>구성은 세 겹이다. (1) 클라이언트 하나에 키를 바꿔 넘겨도 요청마다 넘긴 키가 나가는가,
 * (2) 공용 {@link LlmHttp#client()} 가 키를 들고 있지 않은가, (3) 실제 라우터가 사용자 저장소에서
 * 키를 찾아 바인딩한 포트를 섞어 호출하고 동시에 호출해도 요청과 키의 짝이 맞는가.
 * (3)은 서버가 요청 본문의 사용자 표식과 헤더의 키를 <b>같은 요청에서</b> 함께 읽어 짝을
 * 대조하므로, 순서가 우연히 맞아떨어져 통과하는 일이 없다.</p>
 *
 * <p>GLM(Authorization 헤더)과 Anthropic(x-api-key 헤더) 두 경로를 잰다. 이 둘만 엔드포인트가
 * 설정값이라 로컬 서버로 돌릴 수 있다 — OpenAI 는 URL 이 고정이다. 세 클라이언트는 같은
 * {@link LlmHttp#client()} 위에서 같은 방식(호출마다 요청 헤더)으로 키를 싣는다.</p>
 */
class LlmApiKeyIsolationTest {

    private static final String CHAT_RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
    private static final String ANTHROPIC_RESPONSE =
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}";

    private static final long USER_A = 1L;
    private static final long USER_B = 2L;
    private static final long USER_WITHOUT_KEYS = 99L;

    /** 요청 본문에 심는 사용자 표식. 서버가 이것과 헤더의 키를 한 요청에서 함께 읽는다. */
    private static final Pattern USER_TAG = Pattern.compile("from-user-(\\d+)");

    /** 서버가 한 요청에서 읽은 것. 리스트는 동시 호출에서도 안전해야 한다. */
    private record Seen(String authorization, String xApiKey, String body) {

        /** 본문에 심긴 사용자 표식. 없으면 -1. */
        long taggedUser() {
            Matcher matcher = USER_TAG.matcher(body);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
        }
    }

    private HttpServer server;
    private ExecutorService serverThreads;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private final InMemoryCredentials credentials = new InMemoryCredentials();
    private AiProperties properties;
    private LlmUsageRecorder recorder;

    @BeforeEach
    void startServer() throws Exception {
        // 서버가 요청을 한 스레드로 직렬 처리하면 동시 호출 테스트가 동시성을 못 만든다.
        serverThreads = Executors.newFixedThreadPool(8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, CHAT_RESPONSE));
        server.createContext("/v1/messages", exchange -> respond(exchange, ANTHROPIC_RESPONSE));
        server.start();

        properties = new AiProperties();
        properties.getGlm().setBaseUrl(glmUrl());
        properties.getAnthropic().setBaseUrl(anthropicUrl());
        recorder = new LlmUsageRecorder(mock(LlmUsageStore.class));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private void respond(HttpExchange exchange, String responseBody) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("x-api-key"),
                body));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String glmUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private String anthropicUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    // ── (1) 클라이언트: 넘긴 키가 그 요청에만 실린다 ───────────────────────────────────

    @Test
    void 키를_바꿔_넘기면_다음_요청은_바뀐_키로_나간다() {
        // 예전에는 properties.getGlm().setApiKey(..) 로 키를 바꿨다. 그 자리가 사라졌으므로 이제
        // 키는 호출 인자로만 들어온다. 클라이언트는 모든 사용자가 함께 쓰는 하나의 빈이라,
        // 앞 호출의 키가 인스턴스에 남으면 여기서 곧바로 어긋난다.
        GlmClient client = new GlmClient(properties, recorder);

        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults(), "key-of-user-a");
        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults(), "key-of-user-b");

        assertThat(authorizations()).containsExactly("Bearer key-of-user-a", "Bearer key-of-user-b");
    }

    @Test
    void Anthropic_클라이언트도_넘긴_키를_요청_헤더로_싣고_남기지_않는다() {
        ClaudeClient client = new ClaudeClient(properties, recorder);

        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults(), "anthropic-a");
        client.complete("system", List.of(new LlmMessage("user", "hi")), AiModelOptions.defaults(), "anthropic-b");

        assertThat(seen).extracting(Seen::xApiKey).containsExactly("anthropic-a", "anthropic-b");
        // Anthropic 은 Authorization 이 아니라 x-api-key 로 인증한다 — 다른 제공자의 표기가 섞이면 안 된다.
        assertThat(seen).extracting(Seen::authorization).containsOnlyNulls();
    }

    // ── (2) 공용 HTTP 클라이언트: 어떤 키도 들고 있지 않다 ─────────────────────────────

    @Test
    void 같은_클라이언트로_보내도_요청마다_다른_키가_나간다() {
        // 공용 인스턴스가 어떤 키도 들고 있지 않다는 것을, 그 인스턴스로 직접 두 번 보내 확인한다.
        // Anthropic·OpenAI·GLM 클라이언트가 모두 쓰는 같은 인스턴스다.
        assertThat(LlmHttp.client()).isSameAs(LlmHttp.client());

        send("key-of-user-a");
        send("key-of-user-b");

        assertThat(authorizations()).containsExactly("Bearer key-of-user-a", "Bearer key-of-user-b");
    }

    private void send(String apiKey) {
        LlmHttp.client()
                .post()
                .uri(glmUrl())
                .header("Authorization", "Bearer " + apiKey)
                .body("{}")
                .retrieve()
                .body(String.class);
    }

    // ── (3) 라우터: 사용자 저장소에서 찾은 키가 만든 포트에만 묶인다 ───────────────────

    @Test
    void 두_사용자의_포트를_먼저_만들어_두고_섞어_호출해도_각자_자기_키로_나간다() {
        // 포트를 둘 다 만든 뒤에 호출한다. 라우터가 "마지막으로 조회한 키" 같은 상태를 들고 있다면
        // 먼저 만든 포트도 나중 사용자의 키로 나간다. 바인딩이 포트 단위여야 통과한다.
        credentials.register(USER_A, AiProvider.GLM, glmKey(USER_A));
        credentials.register(USER_B, AiProvider.GLM, glmKey(USER_B));
        LlmRouter router = router();

        LlmPort portA = router.route(AiProvider.GLM, USER_A);
        LlmPort portB = router.route(AiProvider.GLM, USER_B);

        call(portB, USER_B);
        call(portA, USER_A);
        call(portB, USER_B);
        call(portA, USER_A);

        assertThat(authorizations()).containsExactly(
                "Bearer " + glmKey(USER_B), "Bearer " + glmKey(USER_A),
                "Bearer " + glmKey(USER_B), "Bearer " + glmKey(USER_A));
        assertEveryRequestCarriedItsOwnersKey(LlmApiKeyIsolationTest::glmKey, HeaderKind.AUTHORIZATION);
    }

    @Test
    void 여러_사용자가_동시에_호출해도_요청마다_그_요청_주인의_키가_실린다() throws Exception {
        // 서버가 요청 본문의 사용자 표식과 헤더의 키를 같은 요청에서 읽는다. 순서가 아니라
        // "이 요청의 주인 = 이 요청에 실린 키의 주인" 이라는 짝을 대조하므로, 스레드가 어떻게
        // 섞여도 한 건이라도 어긋나면 실패한다.
        int users = 8;
        int callsPerUser = 25;
        for (long user = 1; user <= users; user++) {
            credentials.register(user, AiProvider.GLM, glmKey(user));
        }
        LlmRouter router = router();

        ExecutorService callers = Executors.newFixedThreadPool(users);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long user = 1; user <= users; user++) {
                long owner = user;
                futures.add(callers.submit(() -> {
                    startTogether.await();
                    for (int i = 0; i < callsPerUser; i++) {
                        // 호출마다 라우팅부터 다시 한다 — 실제 서비스 경로와 같다.
                        call(router.route(AiProvider.GLM, owner), owner);
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }

        assertThat(seen).hasSize(users * callsPerUser);
        assertEveryRequestCarriedItsOwnersKey(LlmApiKeyIsolationTest::glmKey, HeaderKind.AUTHORIZATION);
    }

    @Test
    void 도구_루프_라우터도_같은_규칙으로_사용자마다_다른_키를_싣는다() {
        credentials.register(USER_A, AiProvider.GLM, glmKey(USER_A));
        credentials.register(USER_B, AiProvider.GLM, glmKey(USER_B));
        LlmToolRouter toolRouter = toolRouter();

        LlmToolPort portA = toolRouter.route(AiProvider.GLM, USER_A);
        LlmToolPort portB = toolRouter.route(AiProvider.GLM, USER_B);

        callTools(portB, USER_B);
        callTools(portA, USER_A);
        callTools(portB, USER_B);

        assertThat(authorizations()).containsExactly(
                "Bearer " + glmKey(USER_B), "Bearer " + glmKey(USER_A), "Bearer " + glmKey(USER_B));
        assertEveryRequestCarriedItsOwnersKey(LlmApiKeyIsolationTest::glmKey, HeaderKind.AUTHORIZATION);
    }

    @Test
    void 코딩_에이전트_제공자는_사용자의_ANTHROPIC_키로_나간다() {
        // CLAUDE_CODE 는 별도 키가 아니다 — 사용자가 한 번 등록한 Anthropic 키로 나간다.
        // 그리고 사용자마다 자기 키여야 한다.
        credentials.register(USER_A, AiProvider.ANTHROPIC, anthropicKey(USER_A));
        credentials.register(USER_B, AiProvider.ANTHROPIC, anthropicKey(USER_B));
        LlmRouter router = router();

        LlmPort portA = router.route(AiProvider.CLAUDE_CODE, USER_A);
        LlmPort portB = router.route(AiProvider.CLAUDE_CODE, USER_B);
        call(portB, USER_B);
        call(portA, USER_A);

        assertThat(seen).extracting(Seen::xApiKey)
                .containsExactly(anthropicKey(USER_B), anthropicKey(USER_A));
        assertEveryRequestCarriedItsOwnersKey(LlmApiKeyIsolationTest::anthropicKey, HeaderKind.X_API_KEY);
    }

    @Test
    void Anthropic_도구_루프도_사용자마다_자기_키를_싣는다() {
        credentials.register(USER_A, AiProvider.ANTHROPIC, anthropicKey(USER_A));
        credentials.register(USER_B, AiProvider.ANTHROPIC, anthropicKey(USER_B));
        LlmToolRouter toolRouter = toolRouter();

        LlmToolPort portA = toolRouter.route(AiProvider.ANTHROPIC, USER_A);
        LlmToolPort portB = toolRouter.route(AiProvider.ANTHROPIC, USER_B);
        callTools(portA, USER_A);
        callTools(portB, USER_B);
        callTools(portA, USER_A);

        assertThat(seen).extracting(Seen::xApiKey)
                .containsExactly(anthropicKey(USER_A), anthropicKey(USER_B), anthropicKey(USER_A));
        assertEveryRequestCarriedItsOwnersKey(LlmApiKeyIsolationTest::anthropicKey, HeaderKind.X_API_KEY);
    }

    // ── (4) 키가 없으면 요청 자체가 나가지 않는다 — 대신 나갈 키는 어디에도 없다 ───────

    @Test
    void 키가_없는_사용자는_다른_사용자가_키를_등록해_뒀어도_요청이_나가기_전에_막힌다() {
        // 배포에 서버 키가 있던 시절에는 이 사용자도 운영자의 키로 실행됐다. 지금은 라우팅 시점에
        // 실패해야 하고, 그 실패가 다른 사용자의 키를 끌어다 쓰는 것으로 바뀌어서도 안 된다.
        credentials.register(USER_A, AiProvider.GLM, glmKey(USER_A));
        credentials.register(USER_A, AiProvider.ANTHROPIC, anthropicKey(USER_A));
        LlmRouter router = router();
        LlmToolRouter toolRouter = toolRouter();

        assertThatThrownBy(() -> router.route(AiProvider.GLM, USER_WITHOUT_KEYS))
                .isInstanceOf(AiCredentialNotRegisteredException.class)
                // 오류 문구에 다른 사용자의 키가 새면 안 된다.
                .hasMessageNotContaining(glmKey(USER_A))
                .hasMessageNotContaining(anthropicKey(USER_A));
        assertThatThrownBy(() -> router.route(AiProvider.ANTHROPIC, USER_WITHOUT_KEYS))
                .isInstanceOf(AiCredentialNotRegisteredException.class);
        assertThatThrownBy(() -> toolRouter.route(AiProvider.GLM, USER_WITHOUT_KEYS))
                .isInstanceOf(AiCredentialNotRegisteredException.class);
        assertThatThrownBy(() -> router.route(AiProvider.CLAUDE_CODE, USER_WITHOUT_KEYS))
                .isInstanceOf(AiCredentialNotRegisteredException.class);

        assertThat(seen).as("키 없는 호출은 네트워크에 한 건도 나가지 않는다").isEmpty();
    }

    @Test
    void 다른_제공자의_키로_대신_나가지_않는다() {
        // 사용자가 GLM 키만 등록했다면 Anthropic·OpenAI 요청에 그 키가 실려서는 안 된다 —
        // 키는 그 벤더에서만 유효하고, 엉뚱한 곳으로 나가는 것 자체가 유출이다.
        credentials.register(USER_A, AiProvider.GLM, glmKey(USER_A));
        LlmRouter router = router();

        assertThatThrownBy(() -> router.route(AiProvider.ANTHROPIC, USER_A))
                .isInstanceOf(AiCredentialNotRegisteredException.class);
        assertThatThrownBy(() -> router.route(AiProvider.OPENAI, USER_A))
                .isInstanceOf(AiCredentialNotRegisteredException.class);
        assertThatThrownBy(() -> router.route(AiProvider.CODEX, USER_A))
                .isInstanceOf(AiCredentialNotRegisteredException.class);

        assertThat(seen).isEmpty();
    }

    @Test
    void 사용자_없이는_키_조회조차_하지_않고_거절한다() {
        // userId 가 null 인 내부 호출이 "미등록" 으로 오해되거나, 어떤 기본 키로 흘러가면 안 된다.
        credentials.register(USER_A, AiProvider.GLM, glmKey(USER_A));
        LlmRouter router = router();

        assertThatThrownBy(() -> router.route(AiProvider.GLM, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(credentials.lookups()).as("null 사용자로는 저장소를 조회하지 않는다").isZero();
        assertThat(seen).isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private LlmRouter router() {
        return new LlmRouter(
                new ClaudeClient(properties, recorder),
                new OpenAiClient(properties, recorder),
                new GlmClient(properties, recorder),
                new UserAiKeyResolver(credentials));
    }

    private LlmToolRouter toolRouter() {
        return new LlmToolRouter(
                new ClaudeToolClient(properties, recorder),
                new OpenAiToolClient(properties, recorder),
                new GlmToolClient(properties, recorder),
                new UserAiKeyResolver(credentials));
    }

    /** 본문에 사용자 표식을 심어 한 번 호출한다. */
    private static void call(LlmPort port, long user) {
        port.complete("system", List.of(new LlmMessage("user", "from-user-" + user)), AiModelOptions.defaults());
    }

    private static void callTools(LlmToolPort port, long user) {
        port.completeWithTools(
                "system",
                List.of(Map.of("role", "user", "content", "from-user-" + user)),
                List.of(new ToolDefinition("noop", "does nothing", Map.of("type", "object"))),
                AiModelOptions.defaults());
    }

    private List<String> authorizations() {
        return seen.stream().map(Seen::authorization).toList();
    }

    private enum HeaderKind { AUTHORIZATION, X_API_KEY }

    /** 시나리오 전체가 쓰는 키 규칙 — 키만 보면 누구 것인지 알 수 있어야 대조가 가능하다. */
    private static String glmKey(long user) {
        return "glm-key-of-" + user;
    }

    private static String anthropicKey(long user) {
        return "anthropic-key-of-" + user;
    }

    /**
     * 모든 요청에서 "본문이 말하는 주인" 과 "헤더에 실린 키의 주인" 이 같은지 본다. 순서와 무관하다.
     */
    private void assertEveryRequestCarriedItsOwnersKey(java.util.function.LongFunction<String> keyOf, HeaderKind kind) {
        assertThat(seen).isNotEmpty();
        for (Seen request : seen) {
            long owner = request.taggedUser();
            assertThat(owner).as("요청 본문에 사용자 표식이 있어야 한다: %s", request.body()).isPositive();
            String expected = kind == HeaderKind.AUTHORIZATION ? "Bearer " + keyOf.apply(owner) : keyOf.apply(owner);
            String actual = kind == HeaderKind.AUTHORIZATION ? request.authorization() : request.xApiKey();
            assertThat(actual)
                    .as("사용자 %d 의 요청이 다른 사용자의 키로 나갔다", owner)
                    .isEqualTo(expected);
        }
    }

    /**
     * 사용자·벤더로 키를 찾는 저장소의 최소 구현. 조회 외의 연산은 이 테스트에서 불려서는 안 되므로
     * 던진다 — 라우터가 키를 만들거나 지우는 경로로 새는 것을 막는다.
     */
    private static final class InMemoryCredentials implements AiProviderCredentialRepository {

        private final Map<String, AiProviderCredential> byUserAndVendor = new ConcurrentHashMap<>();
        private final AtomicInteger lookups = new AtomicInteger();

        void register(long userId, AiProvider vendor, String apiKey) {
            byUserAndVendor.put(slot(userId, vendor), new AiProviderCredential(userId, vendor, apiKey, null));
        }

        int lookups() {
            return lookups.get();
        }

        @Override
        public Optional<AiProviderCredential> findByUserIdAndProvider(Long userId, AiProvider provider) {
            lookups.incrementAndGet();
            return Optional.ofNullable(byUserAndVendor.get(slot(userId, provider)));
        }

        private static String slot(Long userId, AiProvider vendor) {
            return userId + ":" + vendor;
        }

        @Override
        public AiProviderCredential save(AiProviderCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AiProviderCredential> findByUserIdOrderByProviderAsc(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deleteByUserIdAndProvider(Long userId, AiProvider provider) {
            throw new UnsupportedOperationException();
        }
    }
}
