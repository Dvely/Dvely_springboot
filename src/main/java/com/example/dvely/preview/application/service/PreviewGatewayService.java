package com.example.dvely.preview.application.service;

import com.example.dvely.preview.application.port.out.DeadPreviewSessionReclaimer;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
     * <p>{@code frame-ancestors}는 제3자 사이트가 이 프리뷰를 자기 페이지에 끼워 넣는 것을 막는다.
     * 기본값 {@code 'self'}는 FE와 게이트웨이가 같은 오리진이라는 전제 위에 있는데, 그 전제가
     * 성립하지 않는 환경이 있다 — dev 는 FE 가 각자 로컬(localhost:5173)에서 뜨고 게이트웨이는
     * EC2 라 cross-origin 이고, 그래서 인앱 프리뷰가 통째로 차단됐다(2026-08-16 실측). 프리뷰 접근
     * 쿠키가 SameSite=Lax 라 dev 에서만 401 이 나던 것과 같은 구조다.</p>
     *
     * <p>그래서 허용 오리진을 설정으로 받는다. 운영은 기본값 {@code 'self'} 를 그대로 쓰고, dev 만
     * 로컬 FE 오리진을 더한다. 넓히는 것은 {@code frame-ancestors} 뿐이고 {@code sandbox} 는 그대로라,
     * 위에서 막은 부모 토큰 탈취는 어떤 설정에서도 열리지 않는다.</p>
     *
     * <p>대가: 불투명 오리진이므로 <b>프리뷰 앱 자신의</b> {@code localStorage}·쿠키도 쓸 수 없다.
     * 정적 빌드 미리보기 용도에서는 수용 가능한 손실이며, 그 기능이 필요해지면 프리뷰를 전용
     * 오리진으로 분리하는 것이 정답이다(#77 후속 논의).</p>
     */
    // Spring 의 HttpHeaders 에는 이 이름의 상수가 없다.
    static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    static final String SANDBOX_DIRECTIVES =
            "sandbox allow-scripts allow-forms allow-popups allow-modals";

    /**
     * 프리뷰 컨테이너는 같은 호스트의 루프백이다. 연결이 2 초 걸린다면 느린 게 아니라 컨테이너가
     * 없는 것이다 — 이미 {@code isInnerAppUnreachable} 이 같은 2 초로 죽음을 판정하고 있다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 버퍼링 프록시({@code fetch}) 한 번의 상한.
     *
     * <p>안쪽 앱이 응답을 시작하지 않으면 Tomcat 요청 스레드가 그대로 묶인다. 앞단 nginx 는
     * {@code proxy_read_timeout 3600s} 라 아무것도 끊어주지 않으므로, 여기서 끊지 않으면 정말로
     * 한 시간을 붙잡고 있는다.</p>
     *
     * <p><b>SSE 경로({@code proxyEventStream})에는 붙이지 않는다.</b> 그 요청은 헤더만 받고
     * 스트림을 내려보내는 것이 정상 동작이라, 요청 단위 상한을 걸면 살아 있는 스트림을 끊는다.
     * 대신 연결 상한은 공유하므로 닿지 않는 컨테이너는 SSE 에서도 2 초에 판정된다.</p>
     */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final String contentSecurityPolicy;
    private final boolean reclaimEnabled;
    private final DeadPreviewSessionReclaimer reclaimer;

    // 임시 진단(기본 off). cross-origin iframe 안의 콘솔/DOM 을 밖에서 못 보므로, 프리뷰 문서에 에러·상태를
    // 화면(body)에 그리는 오버레이를 주입해 iframe 스크린샷만으로 렌더 실패 원인을 잡는다. 필드 주입이라
    // 기존 생성자·테스트를 안 건드린다(테스트에선 기본값 false). 원인 확인 후 끈다.
    @Value("${qeploy.preview.gateway-diagnostic-enabled:false}")
    private boolean diagnosticEnabled;

    public PreviewGatewayService(
            @Value("${qeploy.preview.frame-ancestors:'self'}") String frameAncestors,
            @Value("${qeploy.preview.gateway-reclaim-enabled:true}") boolean reclaimEnabled,
            DeadPreviewSessionReclaimer reclaimer) {
        this.contentSecurityPolicy = SANDBOX_DIRECTIVES + "; frame-ancestors " + frameAncestors.trim();
        this.reclaimEnabled = reclaimEnabled;
        this.reclaimer = reclaimer;
    }

    // 테스트가 조립 결과를 직접 확인하기 위한 접근자.
    String sandboxPolicy() {
        return contentSecurityPolicy;
    }

    /** GET 편의 오버로드(본문 없음). 기존 호출부·테스트가 그대로 쓴다. */
    public ResponseEntity<byte[]> proxy(PreviewSessionInfo session,
                                        String gatewayPrefix,
                                        String path,
                                        String query) {
        return proxy("GET", session, gatewayPrefix, path, query, null, null);
    }

    /**
     * 프리뷰 컨테이너로 요청을 프록시한다. GET 뿐 아니라 쓰기(POST/PUT/DELETE/PATCH)도 method·본문을 그대로
     * 전달한다 — 에이전트가 만든 앱의 등록·폼이 동작하려면 필요하다. 응답의 HTML 재작성(base 흡수·경로 shim)은
     * GET 문서에만 적용되고, 쓰기 응답(대개 JSON)은 그대로 돌려준다.
     */
    public ResponseEntity<byte[]> proxy(String method,
                                        PreviewSessionInfo session,
                                        String gatewayPrefix,
                                        String path,
                                        String query,
                                        byte[] requestBody,
                                        String requestContentType) {
        try {
            String safePath = sanitizePath(path);
            HttpResponse<byte[]> response = fetch(method, session, safePath, query, requestBody, requestContentType);
            if ("GET".equalsIgnoreCase(method)) {
                response = absorbBuildBasePath(session, safePath, query, response);
            }

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
                    .header(CONTENT_SECURITY_POLICY, contentSecurityPolicy)
                    .body(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            // 안쪽 앱에 아예 도달하지 못했다(연결 거부/리셋). 컨테이너는 살아있어도 그 안의 서버 프로세스가
            // 죽으면 이 자리에 온다 — attach·findCurrent 의 컨테이너-생존 확인으로는 못 걸러지는 사각이다.
            // 한 번 더 빠르게 확인해 일시적 실패가 아니면 세션을 회수한다(EXPIRED + 컨테이너 제거). 그러면
            // findCurrent 가 "없음"으로 답해 FE 가 새 빌드 CTA 로 자동 복귀한다. 게이트웨이는 host-affine 이라
            // 이 판정은 항상 로컬 컨테이너에 대한 것이다.
            if (reclaimEnabled && isInnerAppUnreachable(session)) {
                reclaimer.reclaimUnreachable(session.sessionId());
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    /**
     * SSE({@code text/event-stream})를 <b>스트리밍</b>으로 프록시한다. 버퍼링 {@code proxy} 는 응답을
     * {@code ofByteArray} 로 통째로 모아서 SSE 처럼 끝나지 않는 응답에선 영원히 막힌다 — 그래서 SSE 는
     * 이 전용 경로로 온다({@code EventSource} 는 {@code Accept: text/event-stream} 을 보내므로 컨트롤러가
     * {@code produces} 로 갈라 여기로 라우팅한다).
     *
     * <p>{@code ofInputStream} 은 본문을 모으지 않고 <b>헤더가 도착하는 즉시</b> 스트림을 돌려주므로,
     * 업스트림이 이벤트를 흘릴 때마다 청크를 그대로 내려보내며 매 청크 {@code flush} 한다(서블릿 컨테이너·
     * 앞단 프록시가 이벤트를 쌓지 않게). {@code X-Accel-Buffering: no} 로 nginx 버퍼링을, {@code no-transform}
     * 으로 CDN 변형을 끈다. 재연결 시 브라우저가 보내는 {@code Last-Event-ID} 는 그대로 앞으로 전달한다.
     * SSE 는 문서가 아니라 데이터라 sandbox CSP 는 붙이지 않는다.</p>
     *
     * <p>업스트림이 SSE 가 아닌 응답을 줘도(엔드포인트 없음 등) 그 {@code Content-Type} 을 그대로 흘려
     * 일반 스트리밍 패스스루로 동작한다. 도달 실패는 버퍼링 경로와 같은 규칙으로 502 + (재확인 후) 회수.</p>
     */
    public ResponseEntity<StreamingResponseBody> proxyEventStream(PreviewSessionInfo session,
                                                                  String path,
                                                                  String query,
                                                                  String lastEventId) {
        String target = "http://127.0.0.1:" + session.hostPort() + "/" + sanitizePath(path);
        if (query != null && !query.isBlank()) {
            target += "?" + query;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target)).GET()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (lastEventId != null && !lastEventId.isBlank()) {
            builder.header("Last-Event-ID", lastEventId);
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            if (reclaimEnabled && isInnerAppUnreachable(session)) {
                reclaimer.reclaimUnreachable(session.sessionId());
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        InputStream upstreamBody = upstream.body();
        StreamingResponseBody stream = out -> {
            try (upstreamBody) {
                byte[] buffer = new byte[512];
                int read;
                while ((read = upstreamBody.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();   // 이벤트를 쌓지 않고 즉시 흘려보낸다
                }
            } catch (Exception ignored) {
                // 클라이언트 끊김/업스트림 종료 — EventSource 가 알아서 재연결한다. 남은 스트림은 try-with 로 닫힌다.
            }
        };
        String contentType = upstream.headers()
                .firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse(MediaType.TEXT_EVENT_STREAM_VALUE);
        return ResponseEntity.status(upstream.statusCode())
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                // nginx 등 리버스 프록시가 이 응답을 버퍼링하지 않게 한다(안 그러면 이벤트가 뭉쳐서 온다).
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    /**
     * 안쪽 앱이 정말 무응답인지 짧게 재확인한다 — 프록시 한 번의 실패로 세션을 지우지 않기 위한 확인
     * 프로브다. 어떤 HTTP 응답이든(상태 코드 무관) 오면 서버 프로세스는 살아있는 것으로 본다. 재확인도
     * 도달하지 못하면(연결 거부/리셋/타임아웃) 프로세스가 죽은 것으로 판정한다. 프리뷰 앱은 스스로
     * 재시작하지 않으므로(agent 가 한 번 띄운 프로세스) 한 번 죽으면 계속 죽어 있어 재확인이 안정적이다.
     */
    private boolean isInnerAppUnreachable(PreviewSessionInfo session) {
        try {
            httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + session.hostPort() + "/"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return false;   // 응답이 왔다 — 프로세스는 살아있다(일시적 실패였음)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;   // 인터럽트는 앱 상태의 증거가 아니다 — 회수하지 않는다
        } catch (Exception e) {
            return true;    // 재확인도 도달 실패 — 안쪽 서버 프로세스가 죽었다
        }
    }

    /** GET 편의 오버로드(base 흡수의 내부 재시도용 — 본문 없음). */
    private HttpResponse<byte[]> fetch(PreviewSessionInfo session, String path, String query)
            throws java.io.IOException, InterruptedException {
        return fetch("GET", session, path, query, null, null);
    }

    private HttpResponse<byte[]> fetch(String method, PreviewSessionInfo session, String path, String query,
                                       byte[] body, String contentType)
            throws java.io.IOException, InterruptedException {
        String target = "http://127.0.0.1:" + session.hostPort() + "/" + path;
        if (query != null && !query.isBlank()) {
            target += "?" + query;
        }
        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(FETCH_TIMEOUT)
                .method(method, publisher);
        if (contentType != null && !contentType.isBlank() && body != null && body.length > 0) {
            builder.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
        html = injectClientShim(html, gatewayPrefix);
        if (diagnosticEnabled) {
            html = injectDiagnostic(html);
        }
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 임시 진단 오버레이 주입(플래그 on 일 때만). cross-origin iframe 은 밖에서 콘솔·DOM 을 못 읽으므로,
     * 문서 안에서 에러·상태를 화면 맨 위 박스에 그려 iframe 스크린샷만으로 원인을 잡게 한다.
     * window.onerror / unhandledrejection / 리소스(스크립트) 로드 에러 / origin·readyState·framed 를 찍는다.
     */
    private String injectDiagnostic(String html) {
        String s = "<script>(function(){"
                + "function b(){var d=document.getElementById('__qd');if(!d){d=document.createElement('div');"
                + "d.id='__qd';d.style.cssText='position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#111;"
                + "color:#0f0;font:12px/1.4 monospace;padding:6px;white-space:pre-wrap;max-height:70%;overflow:auto';"
                + "(document.body||document.documentElement).appendChild(d);}return d;}"
                + "function L(m){try{b().appendChild(document.createTextNode(m+'\\n'));}catch(e){}}"
                + "try{L('origin='+window.origin+' readyState='+document.readyState+' framed='+(window.top!==window.self));}catch(e){L('origin/framed threw: '+e);}"
                + "window.onerror=function(m,src,ln){L('onerror: '+m+' @ '+(src||'')+':'+ln);};"
                + "window.addEventListener('unhandledrejection',function(e){L('reject: '+((e.reason&&e.reason.message)||e.reason));});"
                + "window.addEventListener('error',function(e){var t=e.target;if(t&&t!==window&&(t.src||t.href))L('resErr: '+t.tagName+' '+(t.src||t.href));},true);"
                + "document.addEventListener('DOMContentLoaded',function(){L('DOMContentLoaded');});"
                + "window.addEventListener('load',function(){L('load; #root children='+((document.getElementById('root')||{}).childElementCount));});"
                + "})();</script>";
        int headOpen = html.indexOf("<head");
        if (headOpen >= 0) {
            int headEnd = html.indexOf('>', headOpen);
            if (headEnd >= 0) {
                return html.substring(0, headEnd + 1) + s + html.substring(headEnd + 1);
            }
        }
        return s + html;
    }

    /**
     * 프리뷰는 {@code /api/v1/previews/{sid}/{token}/} 아래서 서빙되는데, 에이전트가 만든 앱은 보통
     * 루트절대경로({@code /api/...})로 자기 백엔드를 부르고, 링크·폼도 루트절대({@code /}, {@code /about})로
     * 건다. 그러면 브라우저가 iframe 오리진 루트로 보내 게이트웨이(앱이 아님)에 닿는다 — API 는
     * "Network error", <b>내비게이션은 401 + XFO 로 프레임 자체가 깨진다</b>(사용자가 처음 본 그 에러).
     * {@code rewriteHtml} 은 <b>초기 HTML</b> 안의 정적 경로만 고칠 뿐 JS 번들의 fetch 나 라우터가
     * <b>런타임에 그리는</b> 앵커는 못 건드린다 — 그래서 앱 스크립트보다 <b>먼저</b> 실행되는 작은 shim 을
     * head 맨 앞에 주입한다. 앱 소스는 건드리지 않고, 같은 오리진 루트절대만 프리뷰 prefix 아래로 다시 쓴다.
     * cross-origin(전체 URL)·protocol-relative({@code //host})·이미 prefix 가 붙은 것·{@code #}·{@code mailto:}
     * 등은 그대로 둔다({@code r()} 이 선행 단일 {@code /} 만 손댄다). 정적 프리뷰는 {@code /api} 호출도 루트절대
     * 링크도 없어 no-op 이다.
     *
     * <p><b>덮는 범위</b>:
     * <ol>
     *   <li><b>데이터</b> — {@code fetch}, {@code XMLHttpRequest}(axios 등). 루트절대를 재작성하므로 SPA
     *       라우팅으로 현재 경로가 바뀌어도 견고하다(상대경로 방식과 달리 base 변화에 안 흔들림).</li>
     *   <li><b>프레임 내비게이션</b> — 캡처 단계 {@code click}/{@code auxclick} 에서 클릭된 앵커의 루트절대
     *       {@code href} 를, {@code submit} 에서 폼의 루트절대 {@code action} 을, 그 자리에서 prefix 로 고친다.
     *       라우터의 {@code <Link>} 는 자체 {@code onClick} 이 {@code preventDefault} 하므로 이 재작성이
     *       무해하고(라우터는 DOM href 가 아니라 {@code to} 로 동작), 평범한 앵커·폼은 prefix 안에서 이동해
     *       프레임이 안 깨진다. 새 탭(cmd/중클릭)도 재작성된 href 를 열어 살아난다. {@code window.open} 도
     *       감싼다(팝업이 루트절대로 열려도 prefix 로 간다).</li>
     * </ol>
     *
     * <p><b>아직 안 덮는 것</b>(의도적, 후속):
     * <ul>
     *   <li>{@code location.assign('/x')}/{@code replace('/x')}, {@code location.href='/x'},
     *       {@code window.location='/x'} — 프로그램적 {@code location} 이동. {@code window.location} 은
     *       보호(unforgeable)돼 있어 그 메서드 재정의가 <b>조용히 무시</b>되고(Chrome 실측 2026-09-06 —
     *       {@code location.assign=fn} 이 no-op), {@code href} 대입은 setter 라 애초에 트랩이 안 된다.
     *       앵커·폼이 압도적 다수라 실효 영향은 작다. 근본 해결은 게이트웨이가 탈출 응답에 프레임 안에서의
     *       복귀 스크립트를 주는 것(별개).</li>
     *   <li>{@code history.pushState}/{@code replaceState} 로 루트절대 — 재작성하면 URL 은 prefix 로 정직해지나
     *       클라이언트 라우터가 {@code basename} 없이 그 prefix 경로를 매칭 못 해 <b>새로고침 초기 렌더가
     *       깨진다</b>. 안 하면 딥링크 새로고침만 깨진다(전진 내비게이션은 라우터 내부 상태로 동작). 둘 다
     *       trade-off 라 건드리지 않는다 — 근본 해결은 생성 앱이 prefix 를 basename 으로 쓰는 것(앱측).</li>
     *   <li>{@code WebSocket} — URL 재작성만으로 안 된다. HTTP Upgrade(101) 핸드셰이크와 양방향 프레임
     *       펌핑이 필요해 이 요청/응답 프록시로는 안 되고, Spring WebSocket 인프라가 있어야 한다(별개 작업).
     *       그래서 WS URL 은 아직 재작성하지 않는다 — prefix 로 보내봐야 업그레이드 못 하는 게이트웨이에
     *       닿아 더 나빠지기 때문. ({@code EventSource}(SSE)는 {@code proxyEventStream} 이 스트리밍으로
     *       지원하므로 위에서 URL 을 재작성한다.)</li>
     * </ul>
     */
    private String injectClientShim(String html, String gatewayPrefix) {
        String prefix = gatewayPrefix.endsWith("/")
                ? gatewayPrefix.substring(0, gatewayPrefix.length() - 1)
                : gatewayPrefix;
        String shim = "<script>(function(){var P=\"" + prefix + "\";"
                + "function r(u){try{if(typeof u===\"string\"&&u.charAt(0)===\"/\"&&u.charAt(1)!==\"/\"&&u.indexOf(P+\"/\")!==0)return P+u;}catch(e){}return u;}"
                // 데이터: fetch / XHR / EventSource(SSE)
                + "if(window.fetch){var f=window.fetch;window.fetch=function(i,o){try{if(typeof i===\"string\")i=r(i);else if(i&&i.url)i=new Request(r(i.url),i);}catch(e){}return f.call(this,i,o);};}"
                + "if(window.XMLHttpRequest&&XMLHttpRequest.prototype&&XMLHttpRequest.prototype.open){var x=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){try{arguments[1]=r(arguments[1]);}catch(e){}return x.apply(this,arguments);};}"
                + "if(window.EventSource){var E=window.EventSource;var NE=function(u,c){return new E(r(u),c);};NE.prototype=E.prototype;try{NE.CONNECTING=E.CONNECTING;NE.OPEN=E.OPEN;NE.CLOSED=E.CLOSED;}catch(e){}window.EventSource=NE;}"
                // 내비게이션: 앵커 href(클릭/중클릭 시점 재작성 — 캡처 단계라 기본 이동 전에 고쳐짐, 라우터 onClick 은 뒤에서 그대로 동작)
                + "function fixA(e){try{var t=e.target;var a=t&&t.closest?t.closest(\"a[href]\"):null;if(!a)return;var h=a.getAttribute(\"href\");var n=r(h);if(n!==h)a.setAttribute(\"href\",n);}catch(e2){}}"
                + "document.addEventListener(\"click\",fixA,true);document.addEventListener(\"auxclick\",fixA,true);"
                // 내비게이션: 폼 action
                + "document.addEventListener(\"submit\",function(e){try{var f=e.target;if(f&&f.tagName===\"FORM\"){var a=f.getAttribute(\"action\");var n=r(a);if(a&&n!==a)f.setAttribute(\"action\",n);}}catch(e2){}},true);"
                // 내비게이션: window.open(팝업). location.assign/replace 는 window.location 이 보호돼 재정의가
                // 조용히 무시되므로(Chrome 실측) 시도하지 않는다 — 위 doc 의 "안 덮는 것" 참고.
                + "try{var wo=window.open;if(wo)window.open=function(){var a=[].slice.call(arguments);if(a.length)a[0]=r(a[0]);return wo.apply(window,a);};}catch(e){}"
                + "})();</script>";
        int headOpen = html.indexOf("<head");
        if (headOpen >= 0) {
            int headEnd = html.indexOf('>', headOpen);
            if (headEnd >= 0) {
                return html.substring(0, headEnd + 1) + shim + html.substring(headEnd + 1);
            }
        }
        return shim + html;
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
