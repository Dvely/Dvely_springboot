package com.example.dvely.preview.application.service;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Slf4j
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
            HttpResponse<byte[]> response = fetch(session, safePath, query);
            response = absorbBuildBasePath(session, safePath, query, response);

            String contentType = response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            byte[] body = response.body();
            boolean html = contentType.contains(MediaType.TEXT_HTML_VALUE);
            if (html) {
                body = rewriteHtml(body, gatewayPrefix);
            }
            return ResponseEntity.status(response.statusCode())
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    // HTML의 no-transform은 CDN이 문서를 건드리지 못하게 한다 (Issue #113).
                    // Cloudflare는 이 zone의 HTML 응답에 자기 RUM beacon을 주입하는데, 프리뷰
                    // 문서는 아래 sandbox로 불투명 오리진이라 그 beacon의 POST가 cross-origin이
                    // 되어 콘솔에 CORS 에러만 남긴다(수집도 되지 않는다). 주입은 HTML에만
                    // 일어나므로 HTML에만 붙여, 자산 응답의 압축은 그대로 둔다.
                    .header(HttpHeaders.CACHE_CONTROL, html ? "no-store, no-transform" : "no-store")
                    // HTML뿐 아니라 모든 프록시 응답에 붙인다. 프리뷰 앱이 자기 JS/워커를 어떤
                    // Content-Type으로 내보내든 실행 컨텍스트는 동일하게 격리돼야 한다.
                    // 불투명 오리진의 CORS 로드(module script 등, Issue #108)는 여기서가 아니라
                    // SecurityConfig 의 /api/v1/previews/** 전용 CORS 설정이 허용한다 — 여기서
                    // ACAO 를 또 달면 CorsFilter 의 것과 중복되어 브라우저가 거절한다.
                    .header(CONTENT_SECURITY_POLICY, SANDBOX_POLICY)
                    .body(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private HttpResponse<byte[]> fetch(PreviewSessionInfo session, String path, String query)
            throws java.io.IOException, InterruptedException {
        String target = "http://127.0.0.1:" + session.hostPort() + "/" + path;
        if (query != null && !query.isBlank()) {
            target += "?" + query;
        }
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(target)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    /**
     * 앱이 배포용 base로 빌드된 경우의 경로 어긋남을 흡수한다 (Issue #111).
     *
     * <p>GitHub Pages는 {@code {owner}.github.io/{repo}/}에서 서빙하므로 앱이 base를
     * {@code /my-todo-app/}으로 두는 것은 정상이다 — 배포 워크플로는 이 값을 무시하고
     * {@code vite build --base=...}로 덮어쓰므로 배포에는 영향이 없다. 그러나 프리뷰 빌드는
     * 순수 {@code npm run build}라 커밋된 base가 그대로 반영되고, index.html은
     * {@code /my-todo-app/assets/app.js}를 참조하게 된다. 컨테이너는 빌드 산출물 <b>루트</b>를
     * 서빙하므로 그 경로에는 아무것도 없고, {@code serve -s}는 없는 경로에 200 + index.html을
     * 돌려준다 — 브라우저는 스타일시트·모듈 자리에서 HTML을 받아 MIME을 거부하고 화면은 백지가 된다.</p>
     *
     * <p>그래서 "확장자가 있는 자산을 요청했는데 HTML이 돌아왔다"를 경로 어긋남의 신호로 삼아
     * 선행 세그먼트를 벗겨 다시 묻는다. base를 미리 알아낼 필요도, 어딘가에 저장할 필요도 없다 —
     * 어떤 툴체인이 어떤 base로 구웠든 같은 방식으로 풀리고, JS 번들 안에 인라인된 동적 import
     * 청크 경로까지 본문을 건드리지 않고 살아난다. base가 없는 프로젝트(대다수)는 첫 요청에서
     * 끝나므로 추가 왕복이 없다.</p>
     *
     * <p>재시도가 실패하면 원래 응답을 그대로 돌려준다. 앱이 의도적으로 확장자 경로에서 HTML을
     * 내보내는 경우(SPA가 처리하는 가짜 경로 등)에도 동작이 달라지지 않는다.</p>
     */
    private HttpResponse<byte[]> absorbBuildBasePath(PreviewSessionInfo session,
                                                     String path,
                                                     String query,
                                                     HttpResponse<byte[]> original)
            throws java.io.IOException, InterruptedException {
        if (!looksLikeStaticAsset(path) || !isHtml(original)) {
            return original;
        }
        // base가 두 단계(/a/b/)인 경우까지만 벗긴다. 그보다 깊은 base는 실물을 본 적이 없고,
        // 한도가 없으면 어긋난 경로 하나가 컨테이너 왕복을 경로 깊이만큼 유발한다.
        String candidate = path;
        for (int depth = 0; depth < 2; depth++) {
            int slash = candidate.indexOf('/');
            if (slash < 0 || slash == candidate.length() - 1) {
                return original;
            }
            candidate = candidate.substring(slash + 1);
            HttpResponse<byte[]> retried = fetch(session, candidate, query);
            if (!isHtml(retried)) {
                log.info("[PreviewGateway] 빌드 base 흡수: {} -> {}", path, candidate);
                return retried;
            }
        }
        return original;
    }

    /**
     * 마지막 세그먼트에 확장자가 있고 그것이 HTML이 아니면 정적 자산 요청으로 본다.
     * SPA 라우트({@code /todos/42})는 확장자가 없어 걸리지 않으므로 fallback 동작을 건드리지 않는다.
     */
    private boolean looksLikeStaticAsset(String path) {
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash < 0 ? path : path.substring(lastSlash + 1);
        int dot = lastSegment.lastIndexOf('.');
        if (dot <= 0 || dot == lastSegment.length() - 1) {
            return false;
        }
        String extension = lastSegment.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return !extension.equals("html") && !extension.equals("htm");
    }

    private boolean isHtml(HttpResponse<byte[]> response) {
        return response.headers()
                .firstValue(HttpHeaders.CONTENT_TYPE)
                .filter(type -> type.contains(MediaType.TEXT_HTML_VALUE))
                .isPresent();
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
