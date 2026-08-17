package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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
        service = new PreviewGatewayService("'self'");
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
        var widened = new PreviewGatewayService("'self' http://localhost:5173");

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

    private PreviewSessionInfo session() {
        return new PreviewSessionInfo(
                "session-1", 1L, 11L, null, null, "container-1",
                container.getAddress().getPort(),
                "https://qeploy.com/api/v1/previews/session-1/token/",
                LocalDateTime.now().plusMinutes(30)
        );
    }
}
