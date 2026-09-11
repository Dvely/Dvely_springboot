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
    // 안쪽 앱 무응답으로 회수 요청된 sessionId 를 기록한다(게이트웨이가 부르는 reclaimer 대역).
    private final java.util.List<String> reclaimed = new java.util.ArrayList<>();

    @BeforeEach
    void startFakeContainer() throws IOException {
        container = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        container.createContext("/", exchange -> {
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
        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

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
        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

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

        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "app.js", null);

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
    void absorbsTheBuildBasePathSoAssetsResolveToTheServedRoot() {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<byte[]> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/assets/app.js", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .contains("application/javascript");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("console.log(1)");
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

        ResponseEntity<byte[]> document = service.proxy(session(), "/api/v1/previews/s/t/", "", null);
        ResponseEntity<byte[]> asset =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);

        assertThat(document.getHeaders().getCacheControl()).contains("no-transform");
        assertThat(asset.getHeaders().getCacheControl()).doesNotContain("no-transform");
        // 캐시 금지는 두 경우 모두 유지된다.
        assertThat(document.getHeaders().getCacheControl()).contains("no-store");
        assertThat(asset.getHeaders().getCacheControl()).contains("no-store");
    }

    /** 루트 자산(favicon 등)도 같은 경로로 살아난다. */
    @Test
    void absorbsTheBasePathForRootLevelAssetsToo() {
        serveAsset("/favicon.svg", "image/svg+xml", "<svg/>");

        ResponseEntity<byte[]> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "my-todo-app/favicon.svg", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("image/svg+xml");
    }

    /** base 가 없는 프로젝트(대다수)는 첫 요청에서 끝나야 한다 — 추가 왕복도, 경로 변형도 없다. */
    @Test
    void leavesAssetsThatAlreadyResolveUntouched() {
        serveAsset("/assets/app.js", "application/javascript", "console.log(1)");

        ResponseEntity<byte[]> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "assets/app.js", null);

        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("console.log(1)");
    }

    /**
     * SPA 라우트는 확장자가 없어 자산으로 보지 않는다 — serve -s 의 index.html fallback 이
     * 그대로 유지돼야 새로고침한 딥링크가 앱으로 들어간다.
     */
    @Test
    void keepsTheSpaFallbackForRoutesThatAreNotAssets() {
        ResponseEntity<byte[]> response =
                service.proxy(session(), "/api/v1/previews/s/t/", "todos/42", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/html");
    }

    /** 접두를 벗겨도 없는 자산은 원래 응답을 그대로 돌려준다 — 경로를 무한히 깎지 않는다. */
    @Test
    void fallsBackToTheOriginalResponseWhenStrippingDoesNotHelp() {
        ResponseEntity<byte[]> response =
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
        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

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

        ResponseEntity<byte[]> response = service.proxy(dead, "/api/v1/previews/s/t/", "", null);

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

        ResponseEntity<byte[]> response = disabled.proxy(dead, "/api/v1/previews/s/t/", "", null);

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
    void injectsApiPathShimIntoHtmlSoRootAbsoluteApiCallsReachTheApp() {
        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String html = new String(response.getBody(), StandardCharsets.UTF_8);
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
    void injectsNavigationShimSoRootAbsoluteLinksAndFormsStayInThePrefix() {
        ResponseEntity<byte[]> response = service.proxy(session(), "/api/v1/previews/s/t/", "", null);

        String html = new String(response.getBody(), StandardCharsets.UTF_8);
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
    void proxiesWriteMethodsWithTheirBodyToTheApp() {
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

        ResponseEntity<byte[]> response = service.proxy(
                "POST", session(), "/api/v1/previews/s/t/", "api/entries", null, reqBody, "application/json");

        assertThat(response.getStatusCode().value()).isEqualTo(201);   // 상태 그대로
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
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

    /** 이 경로에만 실제 파일이 있는 상태를 만든다. 나머지 경로는 @BeforeEach 의 "/" 가 받아 index.html 을 돌려준다(serve -s 와 같은 동작). */
    private void serveAsset(String path, String contentType, String content) {
        container.createContext(path, exchange -> {
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
