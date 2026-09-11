package com.example.dvely.preview.presentation;

import com.example.dvely.common.response.RawApiResponse;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RawApiResponse
@RequiredArgsConstructor
public class PreviewGatewayController {

    static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

    /**
     * 요청 본문 상한 (Issue #342, 7-3). 프리뷰 앱의 폼·업로드가 쓰기에 충분하면서, 컨테이너 메모리
     * 상한(1 GiB)에 비해 작다.
     */
    private static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024;

    /** 문서를 "여는" 요청들 — iframe/새 탭 진입과 그 변종. 여기에만 소유권 쿠키를 요구한다. */
    private static final Set<String> NAVIGATION_DESTS = Set.of("document", "iframe", "frame", "embed", "object");

    private final PreviewSessionService previewSessionService;
    private final PreviewGatewayService previewGatewayService;
    private final PreviewAccessCookies accessCookies;
    private final PreviewProperties previewProperties;

    @Operation(
            summary = "Preview 컨테이너 리버스 프록시",
            description = "sessionId/accessToken(URL에 내장된 발급 즉시 랜덤 토큰 — JWT 아님, iframe에서 별도 " +
                          "Authorization 헤더 없이 접근하기 위함)이 유효하면 요청을 해당 Docker 컨테이너로 그대로 프록시합니다. " +
                          "응답 Content-Type은 프리뷰 앱이 반환하는 값 그대로이며(HTML/JS/CSS/이미지 등), 이 API 자체는 " +
                          "공통 응답 envelope로 감싸지 않습니다(@RawApiResponse). 세션이 없거나 토큰이 일치하지 않으면 404입니다. " +
                          "Swagger UI \"Try it out\"으로 직접 호출하기보다는 taskId 폴링으로 받은 previewUrl을 브라우저에서 여는 용도입니다."
    )
    @RequestMapping(
            method = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.DELETE, RequestMethod.PATCH },
            value = {
                    "/api/v1/previews/{sessionId}/{accessToken}",
                    "/api/v1/previews/{sessionId}/{accessToken}/**"
            })
    public ResponseEntity<Resource> proxy(
            @Parameter(description = "Preview 세션 ID") @PathVariable String sessionId,
            @Parameter(description = "세션 발급 시 함께 생성된 1회성 접근 토큰(랜덤 UUID)") @PathVariable String accessToken,
            @CookieValue(name = PreviewAccessCookies.COOKIE_NAME, required = false) String accessCookie,
            HttpServletRequest request
    ) {
        Authorized authorized = authorize(sessionId, accessToken, accessCookie,
                request.getHeader(SEC_FETCH_DEST));
        if (authorized.error() != null) {
            return ResponseEntity.status(authorized.error()).build();
        }
        PreviewSessionInfo session = authorized.session();
        String prefix = "/api/v1/previews/" + sessionId + "/" + accessToken + "/";
        String path = extractPath(request, prefix);
        // 쓰기(POST/PUT/PATCH)의 본문을 그대로 전달한다. GET/DELETE 는 보통 본문이 없어 빈 배열이 된다.
        // 앱이 JSON 으로 fetch 하므로 Spring 이 본문을 파라미터로 소비하지 않아 원문이 그대로 읽힌다.
        // 본문을 다 읽지 못하면(클라이언트 중단 등) 400 — 컨테이너로 반쪽 요청을 보내지 않는다.
        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (RequestBodyTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return previewGatewayService.proxy(
                session,
                prefix,
                path,
                request.getQueryString(),
                new PreviewGatewayService.ProxiedRequest(
                        request.getMethod(), body, request.getContentType(),
                        // 브라우저의 조건부 요청을 안쪽 앱까지 전달한다 — 자산이 안 바뀌었으면 304 로
                        // 끝나 본문이 흐르지 않는다(Issue #342, 7-2). 인가는 예전과 똑같이 매 요청 돈다.
                        request.getHeader(HttpHeaders.IF_NONE_MATCH),
                        request.getHeader(HttpHeaders.IF_MODIFIED_SINCE))
        );
    }

    /**
     * 요청 본문에 상한을 둔다 (Issue #342, 7-3).
     *
     * <p>예전에는 {@code readAllBytes()} 로 무제한으로 읽었다. 이 경로는 서브리소스 요청에 소유권
     * 쿠키를 요구하지 않으므로(회전 accessToken 이 든 URL 자체가 자격 — {@link #isAuthorized} 참고)
     * 유효한 프리뷰 주소 하나만 쥐면 <b>로그인 없이</b> 임의 크기의 POST 를 보낼 수 있었고, 그 본문이
     * 곧 힙이다. 상한을 넘으면 413 으로 끊는다.</p>
     *
     * <p>{@code Content-Length} 를 먼저 보되 그것만 믿지는 않는다 — 청크 전송은 길이를 안 싣고, 실린
     * 값이 사실이라는 보장도 없다. 그래서 실제로 읽는 양도 상한+1 로 끊는다. 상한은 프리뷰 앱의 폼·
     * 업로드가 쓰기에 충분하고(10 MiB), 컨테이너 메모리 상한(1 GiB)에 비해 작다.</p>
     */
    private byte[] readBoundedBody(HttpServletRequest request) throws java.io.IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
            throw new RequestBodyTooLargeException();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            throw new RequestBodyTooLargeException();
        }
        return body;
    }

    /** 413 으로 갈라 나가기 위한 내부 신호. 밖으로 나가지 않으므로 스택트레이스를 만들지 않는다. */
    private static class RequestBodyTooLargeException extends RuntimeException {
        RequestBodyTooLargeException() {
            super(null, null, false, false);
        }
    }

    @Operation(
            summary = "Preview 컨테이너 SSE 스트리밍 프록시",
            description = "EventSource(text/event-stream) 요청을 스트리밍으로 프록시합니다. 버퍼링 프록시(위 proxy)는 " +
                          "끝나지 않는 SSE 응답에서 막히므로, Accept: text/event-stream 요청은 produces 로 갈려 이 핸들러가 " +
                          "받아 청크 단위로 흘려보냅니다. 그 외 계약(세션/토큰/소유권)은 위 proxy 와 동일합니다."
    )
    @GetMapping(
            value = {
                    "/api/v1/previews/{sessionId}/{accessToken}",
                    "/api/v1/previews/{sessionId}/{accessToken}/**"
            },
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> proxyEventStream(
            @Parameter(description = "Preview 세션 ID") @PathVariable String sessionId,
            @Parameter(description = "세션 발급 시 함께 생성된 접근 토큰") @PathVariable String accessToken,
            @CookieValue(name = PreviewAccessCookies.COOKIE_NAME, required = false) String accessCookie,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request
    ) {
        Authorized authorized = authorize(sessionId, accessToken, accessCookie,
                request.getHeader(SEC_FETCH_DEST));
        if (authorized.error() != null) {
            return ResponseEntity.status(authorized.error()).build();
        }
        String prefix = "/api/v1/previews/" + sessionId + "/" + accessToken + "/";
        return previewGatewayService.proxyEventStream(
                authorized.session(),
                extractPath(request, prefix),
                request.getQueryString(),
                lastEventId
        );
    }

    /** 세션 조회 + 소유권 검증 결과. 두 핸들러(버퍼링/스트리밍)가 공유하되, 응답 타입이 달라 각자 봉투를 만든다. */
    private record Authorized(PreviewSessionInfo session, HttpStatus error) {}

    private Authorized authorize(String sessionId, String accessToken, String accessCookie, String secFetchDest) {
        PreviewSessionInfo session = previewSessionService.resolveGateway(sessionId, accessToken).orElse(null);
        if (session == null) {
            return new Authorized(null, HttpStatus.NOT_FOUND);
        }
        if (!isAuthorized(session, accessCookie, secFetchDest)) {
            // 401: URL은 맞지만 이 브라우저가 소유자임을 증명하지 못했다. FE는
            // POST /preview-sessions/{id}/access 로 발급받은 뒤 다시 열면 된다.
            return new Authorized(null, HttpStatus.UNAUTHORIZED);
        }
        return new Authorized(session, null);
    }

    private String extractPath(HttpServletRequest request, String prefix) {
        String requestUri = request.getRequestURI();
        int prefixIndex = requestUri.indexOf(prefix);
        return prefixIndex < 0 ? "" : requestUri.substring(prefixIndex + prefix.length());
    }

    /**
     * 소유권 증명 여부 (Issue #77 G2, 서브리소스 예외는 Issue #108).
     *
     * <p>이전에는 URL의 accessToken 일치가 곧 허가였다 — 주소를 입수한 누구든, 로그인 없이도 남의
     * 프리뷰를 열 수 있었다. 이제 그 위에 "이 브라우저가 세션 소유자로 발급받은 쿠키를 갖고 있는가"를
     * 얹는다. 쿠키는 세션 경로로 좁혀져 있고 소유자 ID까지 서명에 담기므로, 다른 세션의 쿠키를
     * 가져와 쓸 수 없다.</p>
     *
     * <p>단, 쿠키는 <b>문서 탐색에만</b> 요구한다. CSP {@code sandbox}(#102) 때문에 프리뷰 문서는
     * 불투명 오리진이고, 그 문서가 끌어오는 서브리소스(JS/CSS/이미지)에는 브라우저가 쿠키를 실어주지
     * 않는다({@code SameSite}와 무관하게 module script는 credentials 자체를 보내지 않는다). 요구할 수
     * 없는 것을 요구하면 프리뷰가 백지가 될 뿐이므로, 서브리소스는 URL의 회전 accessToken을 자격으로
     * 통과시킨다. {@code Sec-Fetch-Dest}는 브라우저 신호라 curl로는 위조 가능하다 — 그래서 이 완화는
     * "현재 유효한 토큰 URL을 쥔 자는 다음 회전 전까지 서브리소스를 읽을 수 있다"는 잔여 위험을
     * 남기며, 매 열람 시 회전이 그 창을 소유자의 다음 열람 시점까지로 제한한다. 완전한 해소는 프리뷰
     * 전용 오리진 분리(백로그)다.</p>
     *
     * <p>{@code qeploy.preview.require-access-cookie=false}로 끄면 예전 동작으로 되돌아간다. FE가
     * 발급 호출을 아직 배포하지 못한 환경을 위한 임시 스위치이며, 끈 동안에는 이 갭이 그대로 열려
     * 있다.</p>
     */
    private boolean isAuthorized(PreviewSessionInfo session, String accessCookie, String secFetchDest) {
        if (!previewProperties.isRequireAccessCookie()) {
            return true;
        }
        if (!isNavigation(secFetchDest)) {
            return true;
        }
        return accessCookies.isValid(accessCookie, session.sessionId(), session.ownerUserId());
    }

    /**
     * 헤더가 없으면(curl·구형 클라이언트) 탐색으로 간주한다 — 모던 브라우저는 항상 이 헤더를 보내므로,
     * 헤더 없는 요청까지 서브리소스로 풀어주면 아무 도구로나 쿠키 없이 문서를 읽는 길이 열린다.
     */
    private boolean isNavigation(String secFetchDest) {
        if (secFetchDest == null || secFetchDest.isBlank()) {
            return true;
        }
        return NAVIGATION_DESTS.contains(secFetchDest.trim().toLowerCase(Locale.ROOT));
    }
}
