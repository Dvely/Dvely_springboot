package com.example.dvely.preview.application.service;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class PreviewGatewayService {

    /**
     * 프리뷰 문서를 부모 앱과 격리한다.
     *
     * <p>프리뷰 주소는 서비스와 같은 오리진(`https://qeploy.com/api/v1/previews/...`)이고, FE는
     * 이 문서를 `sandbox` 속성 없는 iframe으로 띄우며 서비스 JWT를 `localStorage`에 둔다
     * (`Dvely_FE` `dcef18f` 실측). 그 조합에서는 프리뷰로 서빙되는 사용자·Agent 작성 코드가
     * `parent.localStorage.getItem('accessToken')` 한 줄로 부모의 토큰을 읽어갈 수 있다 —
     * URL 유출조차 필요 없는, 프리뷰를 띄운 본인 계정에 대한 공격이다.</p>
     *
     * <p>CSP의 {@code sandbox} 지시어는 iframe의 sandbox 속성과 같은 플래그를 응답 쪽에서 강제하므로,
     * FE 배포를 기다리지 않고 서버만으로 닫을 수 있다. {@code allow-same-origin}을 넣지 않는 것이
     * 핵심이다 — 문서가 불투명 오리진을 갖게 되어 부모 접근이 차단된다. 스크립트·폼·팝업은 프리뷰가
     * 프리뷰답게 동작하는 데 필요해 허용하되, 팝업은 sandbox를 물려받는다
     * ({@code allow-popups-to-escape-sandbox}는 넣지 않는다).</p>
     *
     * <p>{@code frame-ancestors 'self'}는 제3자 사이트가 이 프리뷰를 자기 페이지에 끼워 넣는 것을
     * 막는다. 우리 FE는 같은 오리진이라 영향받지 않는다.</p>
     *
     * <p>대가: 불투명 오리진이므로 <b>프리뷰 앱 자신의</b> {@code localStorage}·쿠키도 쓸 수 없다.
     * 정적 빌드 미리보기 용도에서는 수용 가능한 손실이며, 그 기능이 필요해지면 프리뷰를 전용
     * 오리진으로 분리하는 것이 정답이다(#77 후속 논의).</p>
     */
    // Spring 의 HttpHeaders 에는 이 이름의 상수가 없다.
    static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    static final String SANDBOX_POLICY =
            "sandbox allow-scripts allow-forms allow-popups allow-modals; frame-ancestors 'self'";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ResponseEntity<byte[]> proxy(PreviewSessionInfo session,
                                        String gatewayPrefix,
                                        String path,
                                        String query) {
        try {
            String safePath = sanitizePath(path);
            String target = "http://127.0.0.1:" + session.hostPort() + "/" + safePath;
            if (query != null && !query.isBlank()) {
                target += "?" + query;
            }
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(target)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            String contentType = response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            byte[] body = response.body();
            if (contentType.contains(MediaType.TEXT_HTML_VALUE)) {
                body = rewriteHtml(body, gatewayPrefix);
            }
            return ResponseEntity.status(response.statusCode())
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    // HTML뿐 아니라 모든 프록시 응답에 붙인다. 프리뷰 앱이 자기 JS/워커를 어떤
                    // Content-Type으로 내보내든 실행 컨텍스트는 동일하게 격리돼야 한다.
                    .header(CONTENT_SECURITY_POLICY, SANDBOX_POLICY)
                    // 위 sandbox로 프리뷰 문서가 불투명 오리진이 되면서 Vite류 module script는
                    // Origin: null 의 CORS 요청으로 온다(Issue #108). 자격은 URL의 회전
                    // accessToken이지 쿠키가 아니므로 '*'로 연다 — 'null' 오리진은 누구나
                    // sandbox iframe으로 만들 수 있어 좁혀도 효과가 없다.
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private byte[] rewriteHtml(byte[] body, String gatewayPrefix) {
        String html = new String(body, StandardCharsets.UTF_8)
                .replace("src=\"/", "src=\"" + gatewayPrefix)
                .replace("href=\"/", "href=\"" + gatewayPrefix)
                .replace("src='/", "src='" + gatewayPrefix)
                .replace("href='/", "href='" + gatewayPrefix);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid preview path");
        }
        return normalized;
    }
}
