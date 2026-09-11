package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 프리뷰 문서가 부모 앱과 같은 오리진에서 실행되던 문제(Issue #102)를 고정한다.
 *
 * <p>FE는 이 문서를 `sandbox` 없는 iframe으로 띄우고 서비스 JWT를 `localStorage`에 두므로, 격리가
 * 빠지는 순간 프리뷰로 서빙되는 임의의 코드가 `parent.localStorage`를 읽어갈 수 있다. 그래서 이
 * 헤더는 "있으면 좋은 것"이 아니라 회귀 가드가 필요한 계약이다.</p>
 */
class PreviewGatewayServiceTest {

    private HttpServer container;
    private PreviewGatewayService service;
    // 컨테이너로 실제로 나간 요청 수 — base 흡수의 왕복 수를 세기 위한 것(7-4).
    private final java.util.concurrent.atomic.AtomicInteger upstreamRequests =
            new java.util.concurrent.atomic.AtomicInteger();
    // 안쪽 앱 무응답으로 회수 요청된 sessionId 를 기록한다(게이트웨이가 부르는 reclaimer 대역).
    private final java.util.List<String> reclaimed = new java.util.ArrayList<>();

    @BeforeEach
    void startFakeContainer() throws IOException {
        container = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        container.createContext("/", exchange -> {
            upstreamRequests.incrementAndGet();
            byte[] body = "<html><body>preview</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        container.start();
        service = new PreviewGatewayService("'self'", true, id -> {
            reclaimed.add(id);
            return true;
        });
    }

    @AfterEach
    void stopFakeContainer() {
        container.stop(0);
    }

    /**
     * frame-ancestors 만 설정으로 넓힌다. 기본값 'self' 는 FE 와 게이트웨이가 같은 오리진이라는
     * 전제인데 dev 는 그렇지 않아 인앱 프리뷰가 통째로 차단됐다. 넓히더라도 sandbox 는 그대로여야
     * 한다 — 부모 토큰 탈취를 막는 것은 그쪽이고, 이 설정으로 열려서는 안 된다.
     */
    @Test
    void configuredFrameAncestorsWidenFramingButNeverTheSandbox() {
        var widened = new PreviewGatewayService("'self' http://localhost:5173", true, id -> false);

        String policy = widened.sandboxPolicy();

        assertThat(policy).contains("frame-ancestors 'self' http://localhost:5173");
        assertThat(policy).contains("sandbox allow-scripts allow-forms allow-popups allow-modals");
        assertThat(policy).doesNotContain("allow-same-origin");
        assertThat(policy).doesNotContain("allow-popups-to-escape-sandbox");
    }

    @Test
    void sandboxesThePreviewDocumentSoItCannotReachTheParentOrigin() {
        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String policy = response.getHeaders().getFirst(PreviewGatewayService.CONTENT_SECURITY_POLICY);
        assertThat(policy).isNotNull();
        assertThat(policy).contains("sandbox");
        // allow-same-origin 이 들어가는 순간 불투명 오리진이 풀려 parent.localStorage 가 다시 열린다.
        assertThat(policy).doesNotContain("allow-same-origin");
        // 팝업은 sandbox 를 물려받아야 한다.
        assertThat(policy).doesNotContain("allow-popups-to-escape-sandbox");
        assertThat(policy).contains("frame-ancestors 'self'");
    }

    /** 스크립트가 도는 미리보기가 목적이므로 격리가 실행 자체를 막아서는 안 된다. */
    @Test
    void stillAllowsTheScriptsAndFormsAPreviewNeeds() {
        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String policy = response.getHeaders().getFirst(PreviewGatewayService.CONTENT_SECURITY_POLICY);
        assertThat(policy).contains("allow-scripts").contains("allow-forms").contains("allow-popups");
    }

    /** HTML 이 아닌 자산(JS/CSS/이미지)도 같은 실행 컨텍스트에 놓인다. */
    @Test
    void appliesTheSamePolicyToNonHtmlAssets() {
        container.createContext("/app.js", exchange -> {
            byte[] body = "console.log(1)".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/javascript");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "app.js", null);

        assertThat(response.getHeaders().getFirst(PreviewGatewayService.CONTENT_SECURITY_POLICY))
                .contains("sandbox");
    }

    /**
     * 앱이 GitHub Pages 배포용 base(/my-todo-app/)로 빌드되면 index.html 이 그 접두가 붙은
     * 자산을 참조하는데, 컨테이너는 빌드 산출물 루트를 서빙하므로 그 경로에는 아무것도 없다.
     * serve -s 는 없는 경로에 index.html(200)을 주기 때문에 브라우저가 MIME 을 거부하고 화면이
     * 백지가 됐다(Issue #111). 접두를 벗겨 다시 물어보는 것이 이 테스트가 지키는 계약이다.
     */
    @Test
    void absorbsTheBuildBasePathSoAssetsResolveToTheServedRoot() throws IOException {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/assets/app.js", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .contains("application/javascript");
        assertThat(bodyOf(response)).isEqualTo("console.log(1)");
    }

    /**
     * Cloudflare는 이 zone의 HTML 응답에 자기 RUM beacon을 주입하는데, 프리뷰 문서는 sandbox로
     * 불투명 오리진이라 그 beacon의 POST가 cross-origin이 되어 콘솔에 CORS 에러만 남는다
     * (Issue #113). no-transform이 주입 자체를 막는다 — 주입은 HTML에만 일어나므로 자산 응답에는
     * 붙이지 않아 CDN 압축을 잃지 않는다.
     */
    @Test
    void tellsTheCdnNotToInjectIntoThePreviewDocument() {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<Resource> document = service.proxy(session(), "/api/v1/previews/s/t/", "", null);
        ResponseEntity<Resource> asset =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);

        assertThat(document.getHeaders().getCacheControl()).contains("no-transform");
        assertThat(asset.getHeaders().getCacheControl()).doesNotContain("no-transform");
        // 문서는 여전히 아예 담지 않는다(7-2 가 자산만 캐시 가능하게 했다).
        assertThat(document.getHeaders().getCacheControl()).contains("no-store");
    }

    /** 루트 자산(favicon 등)도 같은 경로로 살아난다. */
    @Test
    void absorbsTheBasePathForRootLevelAssetsToo() {
        serveAsset("/favicon.svg", "image/svg+xml", "<svg/>");

        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/favicon.svg", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("image/svg+xml");
    }

    /** base 가 없는 프로젝트(대다수)는 첫 요청에서 끝나야 한다 — 추가 왕복도, 경로 변형도 없다. */
    @Test
    void leavesAssetsThatAlreadyResolveUntouched() throws IOException {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);

        assertThat(bodyOf(response)).isEqualTo("console.log(1)");
    }

    /**
     * SPA 라우트는 확장자가 없어 자산으로 보지 않는다 — serve -s 의 index.html fallback 이
     * 그대로 유지돼야 새로고침한 딥링크가 앱으로 들어간다.
     */
    @Test
    void keepsTheSpaFallbackForRoutesThatAreNotAssets() {
        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "todos/42", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/html");
    }

    /** 접두를 벗겨도 없는 자산은 원래 응답을 그대로 돌려준다 — 경로를 무한히 깎지 않는다. */
    @Test
    void fallsBackToTheOriginalResponseWhenStrippingDoesNotHelp() {
        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "a/b/c/missing.js", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/html");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 불투명 오리진의 CORS 로드(Issue #108)는 SecurityConfig 의 previews 전용 CORS 설정이
     * 담당한다({@code CorsConfigurationTest} 참고). 프록시가 ACAO 를 직접 달면 CorsFilter 의
     * 것과 중복되어 브라우저가 "multiple values" 로 거절하므로, 여기서는 안 다는 것이 계약이다.
     */
    @Test
    void doesNotSetItsOwnCorsHeaderBecauseTheCorsFilterOwnsIt() {
        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    /**
     * 컨테이너는 있으나 안쪽 앱이 죽어 도달 불가면(연결 거부) 502 를 돌려주고, 재확인 후 세션을 회수하도록
     * reclaimer 를 부른다 — attach·findCurrent 가 못 걸러내는 "컨테이너 alive + 앱 死" 사각을 게이트웨이가
     * 관찰해 닫는다. 닫힌 포트를 가리켜 도달 실패를 만든다.
     */
    @Test
    void reclaimsTheSessionWhenTheInnerAppIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }   // 닫힘 — 이 포트에는 아무도 리슨하지 않는다
        PreviewSessionInfo dead = new PreviewSessionInfo(
                "session-dead", 1L, 11L, null, null, "container-dead", closedPort,
                "https://qeploy.com/api/v1/previews/session-dead/token/", LocalDateTime.now().plusMinutes(30));

        ResponseEntity<Resource> response = service.proxy(dead, "/api/v1/previews/s/t/", "", null);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(reclaimed).containsExactly("session-dead");   // 안쪽 앱 死 → 세션 회수 요청
    }

    /**
     * 킬스위치가 꺼져 있으면(gateway-reclaim-enabled=false) 도달 불가여도 회수하지 않는다 — 502 만 돌려준다.
     * host_port 재할당 등으로 회수가 오판할 때 재배포 없이 즉시 끌 수 있는 안전장치.
     */
    @Test
    void reclaimDisabled_returns502ButNeverReclaims() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        PreviewGatewayService disabled = new PreviewGatewayService("'self'", false, reclaimed::add);
        PreviewSessionInfo dead = new PreviewSessionInfo(
                "session-dead", 1L, 11L, null, null, "container-dead", closedPort,
                "https://qeploy.com/api/v1/previews/session-dead/token/", LocalDateTime.now().plusMinutes(30));

        ResponseEntity<Resource> response = disabled.proxy(dead, "/api/v1/previews/s/t/", "", null);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(reclaimed).isEmpty();   // 킬스위치 off — 회수 안 함
    }

    /** 정상 응답이면 절대 회수하지 않는다 — 멀쩡한 프리뷰를 관찰만으로 지우면 안 된다. */
    @Test
    void doesNotReclaimAHealthySession() {
        service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        assertThat(reclaimed).isEmpty();
    }

    /**
     * 앱이 루트절대경로(/api/entries)로 자기 백엔드를 부르면 iframe 오리진 루트(게이트웨이)로 나가 실패한다.
     * HTML 에 fetch/XHR 를 감싸 그 요청을 프리뷰 prefix 아래로 재작성하는 shim 이 주입돼야 데이터가 앱에 닿는다.
     */
    @Test
    void injectsApiPathShimIntoHtmlSoRootAbsoluteApiCallsReachTheApp() throws IOException {
        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String html = bodyOf(response);
        assertThat(html).contains("window.fetch");                       // fetch 래핑
        assertThat(html).contains("XMLHttpRequest.prototype.open");      // XHR 래핑(axios 등)
        assertThat(html).contains("/api/v1/previews/s/t");               // prefix(슬래시 뺀)가 shim 에 박힘
    }

    /**
     * 앱이 루트절대 링크(/about)·폼(action="/submit")을 걸면 프레임이 게이트웨이 루트로 이동해
     * 401 + XFO 로 통째로 깨진다(사용자가 처음 본 그 에러). fetch/XHR 뿐 아니라 <b>내비게이션</b>도
     * prefix 안에 붙잡는 shim(클릭/중클릭 앵커 href·폼 action 재작성, window.open 래핑)이 주입돼야 한다.
     * 실제 브라우저 동작(재작성이 실제로 일어나는지)은 별도로 실측 검증했고, 여기서는 그 조각들이
     * 문서에 주입된다는 계약을 회귀 가드로 고정한다.
     */
    @Test
    void injectsNavigationShimSoRootAbsoluteLinksAndFormsStayInThePrefix() throws IOException {
        ResponseEntity<Resource> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String html = bodyOf(response);
        assertThat(html).contains("addEventListener(\"click\",fixA,true)");    // 앵커 클릭 가로채기
        assertThat(html).contains("addEventListener(\"auxclick\",fixA,true)"); // 중클릭(새 탭)도
        assertThat(html).contains("addEventListener(\"submit\"");              // 폼 action 가로채기
        assertThat(html).contains("setAttribute(\"action\"");                  // 폼 action 재작성
        assertThat(html).contains("window.open=function");                     // 팝업 래핑
    }

    /**
     * 쓰기(POST)도 method·본문을 그대로 컨테이너로 전달하고 응답을 돌려준다 — 에이전트가 만든 앱의 등록·폼이
     * 프리뷰에서 동작하려면 필요하다. 앱이 method 와 본문을 되돌려주는 엔드포인트로 왕복을 확인한다.
     */
    @Test
    void proxiesWriteMethodsWithTheirBodyToTheApp() throws IOException {
        container.createContext("/api/entries", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            String out = "{\"method\":\"" + exchange.getRequestMethod() + "\",\"echo\":"
                    + new String(in, StandardCharsets.UTF_8) + "}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(201, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        byte[] reqBody = "{\"name\":\"a\",\"message\":\"hi\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Resource> response = service.proxy(
                session(), "/api/v1/previews/s/t/", "api/entries", null,
                new PreviewGatewayService.ProxiedRequest("POST", reqBody, "application/json", null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);   // 상태 그대로
        String body = bodyOf(response);
        assertThat(body).contains("\"method\":\"POST\"");   // 메서드 그대로 전달
        assertThat(body).contains("\"name\":\"a\"");        // 본문 그대로 전달
    }

    /**
     * SSE({@code text/event-stream})는 버퍼링이 아니라 스트리밍으로 프록시돼야 한다 — 버퍼링 경로는
     * 끝나지 않는 SSE 응답에서 영원히 막힌다. 업스트림이 이벤트를 흘리고 닫으면, 스트리밍 응답이
     * 그 이벤트를 그대로 통과시키고 SSE 계약 헤더(text/event-stream, no-cache/no-transform, 프록시
     * 버퍼링 끄기)를 단다는 것을 고정한다.
     */
    @Test
    void streamsServerSentEventsWithTheStreamingContract() throws Exception {
        container.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, 0);   // 0 = 청크(길이 미정) — SSE 처럼 열린 채로 흘린다
            var os = exchange.getResponseBody();
            os.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: two\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            exchange.close();   // 유한 스트림으로 닫아 writeTo 가 끝나게 한다
        });

        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> response =
                service.proxyEventStream(session(), "events", null, null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/event-stream");
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-transform");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");

        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(sink);   // 업스트림이 닫힐 때까지 청크를 흘려보낸다
        String streamed = sink.toString(StandardCharsets.UTF_8);
        assertThat(streamed).contains("data: one").contains("data: two");
    }

    // ── base 흡수 단수 기억 (Issue #342, 7-4) ─────────────────────────────────────────

    /**
     * base 를 쓰는 프로젝트에서는 자산마다 "틀린 경로로 먼저 묻고 → 접두를 벗겨 다시 묻는" 탐색이
     * 반복됐다. 자산 수 × 최대 3 회의 컨테이너 왕복이다. 한 세션의 자산은 같은 빌드 산출물이라
     * base 도 하나이므로, 두 번째 자산부터는 <b>한 번에</b> 맞아야 한다.
     */
    @Test
    void remembersTheAbsorbedBaseSoLaterAssetsCostOneRoundTrip() throws IOException {
        serveAsset("/assets/first.js", "application/javascript", "console.log(1)");
        serveAsset("/assets/second.js", "application/javascript", "console.log(2)");

        service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/assets/first.js", null);
        int firstAssetRoundTrips = upstreamRequests.get();
        upstreamRequests.set(0);
        ResponseEntity<Resource> second =
                service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/assets/second.js", null);

        assertThat(firstAssetRoundTrips).isEqualTo(2);                       // 첫 자산: 틀린 경로 1 + 벗긴 경로 1
        assertThat(upstreamRequests.get()).isEqualTo(1);   // 두 번째부터: 바로 맞는다
        assertThat(bodyOf(second)).isEqualTo("console.log(2)");
    }

    /**
     * 기억이 틀린 경우(같은 세션에 base 가 다른 자산이 섞임)에도 자산이 살아나야 한다 — 최적화가
     * 동작을 바꾸지 않는다는 것이 이 되돌림의 목적이다.
     */
    @Test
    void fallsBackToTheSearchWhenTheRememberedDepthDoesNotFit() throws IOException {
        serveAsset("/assets/first.js", "application/javascript", "console.log(1)");

        service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/assets/first.js", null);
        // 이번에는 접두가 없는 경로 — 기억한 단수를 적용하면 어긋난다.
        ResponseEntity<Resource> direct =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/first.js", null);

        assertThat(bodyOf(direct)).isEqualTo("console.log(1)");
    }

    // ── 자산 캐시 정책 (Issue #342, 7-2) ─────────────────────────────────────────────

    /**
     * <b>이 프로젝트에서 캐시의 유일한 위험은 {@code public} 이다.</b> 프리뷰 주소의 accessToken 은
     * 소유자가 다시 열 때마다 회전하고, 회전의 목적은 흘러나간 주소가 곧 죽는 것이다. 공유 캐시가
     * 응답을 담으면 원본을 거치지 않고 남에게 내주게 되어 그 회전이 무력화된다 — 어떤 분기에서도
     * {@code private} 여야 한다는 것이 이 테스트가 지키는 계약이다.
     */
    @Test
    void neverLetsAnySharedCacheStoreAPreviewResponse() {
        serveAsset("/assets/index-a1b2c3d4.js", "application/javascript", "console.log(1)");
        serveAsset("/assets/app.js", "application/javascript", "console.log(2)");

        String hashed = cacheControlOf("assets/index-a1b2c3d4.js");
        String plain = cacheControlOf("assets/app.js");
        String document = cacheControlOf("");

        assertThat(hashed).contains("private").doesNotContain("public");
        assertThat(plain).contains("private").doesNotContain("public");
        // 문서는 아예 담지 않는다 — 회전 토큰이 든 prefix 를 본문에 박아 내보내기 때문이다.
        assertThat(document).contains("no-store").doesNotContain("public");
    }

    /** 내용 해시가 박힌 자산만 장기 캐시한다. */
    @Test
    void cachesContentHashedAssetsForALongTime() {
        serveAsset("/assets/index-a1b2c3d4.js", "application/javascript", "console.log(1)");

        assertThat(cacheControlOf("assets/index-a1b2c3d4.js"))
                .isEqualTo("private, max-age=3600, immutable");
    }

    /**
     * 해시로 보이지 않는 이름은 매번 원본에 물어본다 — 세션 조회·인가·토큰 회전 판정이 예전과
     * 똑같이 요청마다 돌고, 절약되는 것은 본문 전송뿐이다.
     */
    @Test
    void makesEverythingElseRevalidateOnEveryRequest() {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        assertThat(cacheControlOf("assets/app.js")).isEqualTo("private, no-cache");
    }

    /**
     * 날짜가 붙은 사람이 지은 이름을 해시로 오인하면 안 된다 — {@code 20260911} 도 16 진수 8 자라,
     * 글자 조건이 없으면 파일을 갈아끼워도 한 시간 동안 예전 것이 보인다.
     */
    @Test
    void doesNotMistakeAHumanNamedFileForAContentHash() {
        serveAsset("/img/photo-20260911.jpg", "image/jpeg", "x");
        serveAsset("/img/logo-v2.png", "image/png", "y");

        assertThat(cacheControlOf("img/photo-20260911.jpg")).isEqualTo("private, no-cache");
        assertThat(cacheControlOf("img/logo-v2.png")).isEqualTo("private, no-cache");
    }

    /** 오류 응답은 캐시하지 않는다 — 404 를 담아두면 컨테이너가 되살아나도 깨진 화면이 유지된다. */
    @Test
    void neverCachesAnErrorResponse() {
        container.createContext("/assets/gone-a1b2c3d4.js", exchange -> {
            upstreamRequests.incrementAndGet();
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/javascript");
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThat(cacheControlOf("assets/gone-a1b2c3d4.js")).isEqualTo("no-store");
    }

    /**
     * 업스트림의 검증자를 넘겨주고, 브라우저가 그것을 되돌려주면 그대로 안쪽 앱에 물어본다. 신선도
     * 판정은 앱이 하고 게이트웨이는 추측하지 않는다 — 바뀌지 않았으면 304 로 본문이 흐르지 않는다.
     */
    @Test
    void passesValidatorsThroughSoAReloadTransfersNothing() {
        container.createContext("/assets/app.js", exchange -> {
            upstreamRequests.incrementAndGet();
            String inm = exchange.getRequestHeaders().getFirst(HttpHeaders.IF_NONE_MATCH);
            exchange.getResponseHeaders().add(HttpHeaders.ETAG, "\"v1\"");
            if ("\"v1\"".equals(inm)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = "console.log(1)".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/javascript");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ResponseEntity<Resource> first =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);
        ResponseEntity<Resource> revalidated = service.proxy(
                session(), "/api/v1/previews/s/t/", "assets/app.js", null,
                new PreviewGatewayService.ProxiedRequest("GET", null, null, "\"v1\"", null));

        assertThat(first.getHeaders().getETag()).isEqualTo("\"v1\"");
        assertThat(revalidated.getStatusCode().value()).isEqualTo(304);
        assertThat(revalidated.getBody()).isNull();                 // 본문이 흐르지 않는다
        assertThat(revalidated.getHeaders().getETag()).isEqualTo("\"v1\"");
        // 304 여도 격리는 그대로 붙는다.
        assertThat(revalidated.getHeaders().getFirst(PreviewGatewayService.CONTENT_SECURITY_POLICY))
                .contains("sandbox");
    }

    /**
     * 문서에는 업스트림 검증자를 넘기지 않는다 — 우리가 내보내는 본문은 shim 을 주입해 재작성한
     * 것이라, 앱의 ETag 는 그 본문의 것이 아니다. 넘기면 브라우저가 shim 없는 원본을 되살린다.
     */
    @Test
    void neverPassesTheDocumentValidatorThroughBecauseTheDocumentIsRewritten() {
        container.createContext("/doc.html", exchange -> {
            upstreamRequests.incrementAndGet();
            byte[] body = "<html><head></head><body>doc</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "text/html");
            exchange.getResponseHeaders().add(HttpHeaders.ETAG, "\"doc-v1\"");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ResponseEntity<Resource> document =
                service.proxy(session(), "/api/v1/previews/s/t/", "doc.html", null);

        assertThat(document.getHeaders().getETag()).isNull();
        assertThat(document.getHeaders().getCacheControl()).contains("no-store");
    }

    private String cacheControlOf(String path) {
        return service.proxy(session(), "/api/v1/previews/s/t/", path, null)
                .getHeaders().getCacheControl();
    }

    /** 응답 본문을 문자열로 읽는다 — 스트리밍 봉투(Resource)로 바뀌어도 테스트가 같은 것을 본다. */
    private String bodyOf(ResponseEntity<Resource> response) throws IOException {
        try (var in = response.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 비-HTML 자산은 힙에 모으지 않고 스트림으로 넘어간다 (Issue #342, 7-3). 예전에는 모든 응답을
     * {@code ofByteArray} 로 전량 버퍼링해, 큰 이미지·번들 하나가 요청마다 그 크기만큼 힙을 썼다.
     * 봉투 타입이 회귀 가드다 — {@code ByteArrayResource} 로 돌아가면 버퍼링이 돌아온 것이다.
     */
    @Test
    void streamsNonHtmlAssetsInsteadOfBufferingThemInHeap() {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<Resource> asset =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);

        assertThat(asset.getBody()).isInstanceOf(InputStreamResource.class);
    }

    /** 큰 자산도 내용이 온전히 통과해야 한다 — 스트리밍으로 바꾼 뒤에도 바이트가 깎이지 않는다. */
    @Test
    void streamsALargeAssetWithoutLosingBytes() throws IOException {
        byte[] large = new byte[3 * 1024 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        container.createContext("/assets/big.bin", exchange -> {
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
            exchange.sendResponseHeaders(200, large.length);
            exchange.getResponseBody().write(large);
            exchange.close();
        });

        ResponseEntity<Resource> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/big.bin", null);

        assertThat(response.getBody()).isInstanceOf(InputStreamResource.class);
        // 업스트림이 길이를 알려줬으므로 그대로 넘긴다(본문을 변형하지 않으니 여전히 정확하다).
        assertThat(response.getHeaders().getContentLength()).isEqualTo(large.length);
        try (var in = response.getBody().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(large);
        }
    }

    /**
     * HTML 은 여전히 버퍼링한다 — shim 주입·경로 재작성이 본문 전체를 봐야 하기 때문이다. 문서가
     * 스트림으로 새면 그 격리·보정이 통째로 빠진다.
     */
    @Test
    void stillBuffersTheDocumentBecauseItHasToBeRewritten() {
        ResponseEntity<Resource> document = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        assertThat(document.getBody()).isInstanceOf(ByteArrayResource.class);
    }

    /** 이 경로에만 실제 파일이 있는 상태를 만든다. 나머지 경로는 @BeforeEach 의 "/" 가 받아 index.html 을 돌려준다(serve -s 와 같은 동작). */
    private void serveAsset(String path, String contentType, String content) {
        container.createContext(path, exchange -> {
            upstreamRequests.incrementAndGet();
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    /**
     * 버퍼링 프록시({@code fetch})에 건 요청 단위 상한이 SSE 경로로 새지 않는다는 것을 고정한다.
     *
     * <p>SSE 요청은 헤더만 받고 스트림을 오래 열어두는 것이 정상 동작이다. 여기에 요청 상한을
     * 붙이면 살아 있는 실시간 스트림을 상한 시점에 끊는다 — 프리뷰 앱 쪽에서는 원인 없이
     * 재연결이 반복되는 모습으로만 보인다.</p>
     */
    @Test
    void 스트리밍_경로는_응답이_시작된_뒤_한참_있다_온_이벤트도_흘려보낸다() throws Exception {
        container.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                // 연결 상한(2 초)보다 길게 쉰다 — 그 값이 스트림 수명으로 새어도 여기서 걸린다.
                Thread.sleep(2500);
                out.write("data: after-a-long-pause\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> response =
                service.proxyEventStream(session(), "events", null, null);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        response.getBody().writeTo(sink);

        assertThat(sink.toString(StandardCharsets.UTF_8))
                .contains("data: first")
                .contains("data: after-a-long-pause");
    }

    private PreviewSessionInfo session() {
        return new PreviewSessionInfo(
                "session-1", 1L, 11L, null, null, "container-1",
                container.getAddress().getPort(),
                "https://qeploy.com/api/v1/previews/session-1/token/",
                LocalDateTime.now().plusMinutes(30)
        );
    }
}
