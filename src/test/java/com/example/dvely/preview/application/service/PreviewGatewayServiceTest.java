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
        service = new PreviewGatewayService();
    }

    @AfterEach
    void stopFakeContainer() {
        container.stop(0);
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
     * sandbox 로 불투명 오리진이 된 문서의 module script 는 Origin: null 의 CORS 요청으로
     * 온다(Issue #108). 이 헤더가 빠지면 Vite 류 빌드의 메인 번들이 차단되어 프리뷰가 백지가 된다.
     */
    @Test
    void allowsCorsLoadsFromTheOpaqueOriginTheSandboxCreates() {
        ResponseEntity<byte[]> html = service.proxy(session(), "/api/v1/previews/s/t/", "", null);
        ResponseEntity<byte[]> asset = service.proxy(session(), "/api/v1/previews/s/t/", "app.js", null);

        assertThat(html.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
        assertThat(asset.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
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
