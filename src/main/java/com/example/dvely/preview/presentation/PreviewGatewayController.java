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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RawApiResponse
@RequiredArgsConstructor
public class PreviewGatewayController {

    static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

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
    @GetMapping({
            "/api/v1/previews/{sessionId}/{accessToken}",
            "/api/v1/previews/{sessionId}/{accessToken}/**"
    })
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "Preview 세션 ID") @PathVariable String sessionId,
            @Parameter(description = "세션 발급 시 함께 생성된 1회성 접근 토큰(랜덤 UUID)") @PathVariable String accessToken,
            @CookieValue(name = PreviewAccessCookies.COOKIE_NAME, required = false) String accessCookie,
            HttpServletRequest request
    ) {
        PreviewSessionInfo session = previewSessionService.resolveGateway(sessionId, accessToken)
                .orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isAuthorized(session, accessCookie, request.getHeader(SEC_FETCH_DEST))) {
            // 401: URL은 맞지만 이 브라우저가 소유자임을 증명하지 못했다. FE는
            // POST /preview-sessions/{id}/access 로 발급받은 뒤 다시 열면 된다.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String prefix = "/api/v1/previews/" + sessionId + "/" + accessToken + "/";
        String requestUri = request.getRequestURI();
        int prefixIndex = requestUri.indexOf(prefix);
        String path = prefixIndex < 0 ? "" : requestUri.substring(prefixIndex + prefix.length());
        return previewGatewayService.proxy(
                session,
                prefix,
                path,
                request.getQueryString()
        );
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
