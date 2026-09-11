package com.example.dvely.preview.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 게이트웨이 인가 (Issue #77 G2).
 *
 * <p>이전에는 URL의 accessToken 일치가 곧 허가라, 주소를 입수한 누구든 로그인 없이 남의 프리뷰를
 * 열 수 있었다. 소유권 쿠키 없이는 열리지 않는다는 것이 이 테스트가 지키는 계약이다.</p>
 */
class PreviewGatewayControllerTest {

    private static final String SESSION_ID = "session-1";
    private static final String ACCESS_TOKEN = "token-1";
    private static final Long OWNER = 7L;

    private PreviewSessionService sessionService;
    private PreviewGatewayService gatewayService;
    private PreviewAccessCookies accessCookies;
    private PreviewProperties properties;
    private PreviewGatewayController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(PreviewSessionService.class);
        gatewayService = mock(PreviewGatewayService.class);
        accessCookies = new PreviewAccessCookies(
                new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L));
        properties = new PreviewProperties();
        controller = new PreviewGatewayController(sessionService, gatewayService, accessCookies, properties);

        when(sessionService.resolveGateway(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.of(session()));
        when(gatewayService.proxy(any(), anyString(), anyString(), any(), any()))
                .thenReturn(ResponseEntity.ok(new ByteArrayResource("body".getBytes())));
    }

    @Test
    void rejectsARequestWithoutTheOwnershipCookieEvenWhenTheUrlIsCorrect() {
        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(gatewayService, never()).proxy(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsACookieIssuedForAnotherSession() {
        String foreign = accessCookies.issue("other-session", OWNER, Duration.ofMinutes(30));

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, foreign, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsACookieIssuedForAnotherUser() {
        String foreign = accessCookies.issue(SESSION_ID, 99L, Duration.ofMinutes(30));

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, foreign, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void servesTheOwnerWhoPresentsTheIssuedCookie() {
        String cookie = accessCookies.issue(SESSION_ID, OWNER, Duration.ofMinutes(30));

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, cookie, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gatewayService).proxy(any(), anyString(), anyString(), any(), any());
    }

    /** 세션 자체가 없으면(만료·오토큰) 쿠키 이전에 404다 — 존재 여부를 인가로 흘리지 않는다. */
    @Test
    void keepsReturningNotFoundForAnUnknownSession() {
        when(sessionService.resolveGateway(SESSION_ID, "wrong")).thenReturn(Optional.empty());

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, "wrong", null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * sandbox(불투명 오리진) 문서가 끌어오는 서브리소스에는 브라우저가 쿠키를 실어주지 않는다
     * (Issue #108 — SameSite 와 무관하게 module script 는 credentials 자체를 보내지 않는다).
     * 회전 accessToken 이 든 URL 자체가 자격이므로 쿠키 없이 통과해야 프리뷰가 백지가 되지 않는다.
     */
    @Test
    void servesSubresourceRequestsWithoutTheCookie() {
        ResponseEntity<Resource> script = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("script"));
        ResponseEntity<Resource> fetch = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("empty"));

        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetch.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 문서를 "여는" 요청은 여전히 쿠키 게이트다 — iframe 진입이든 새 탭(document)이든. */
    @Test
    void keepsRequiringTheCookieForDocumentNavigations() {
        assertThat(controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("iframe")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("document")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** FE가 발급 호출을 아직 배포하지 못한 환경을 위한 임시 스위치. */
    @Test
    void canBeTurnedOffForEnvironmentsWhoseClientHasNotShippedTheAccessCallYet() {
        properties.setRequireAccessCookie(false);

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 요청 본문 상한 (Issue #342, 7-3) ──────────────────────────────────────────────

    /**
     * 이 경로는 서브리소스 요청에 소유권 쿠키를 요구하지 않는다(회전 accessToken 이 든 URL 자체가
     * 자격 — {@code isAuthorized} 참고). 그래서 유효한 프리뷰 주소 하나만 쥐면 로그인 없이 임의
     * 크기의 POST 를 보낼 수 있었고, {@code readAllBytes()} 는 그것을 전부 힙에 올렸다. 선언된
     * 길이가 상한을 넘으면 본문을 읽기도 전에 413 이어야 한다.
     */
    @Test
    void rejectsARequestWhoseDeclaredBodyExceedsTheLimit() {
        HttpServletRequest request = request("empty");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentLengthLong()).thenReturn(11L * 1024 * 1024);

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(gatewayService, never()).proxy(any(), anyString(), anyString(), any(), any());
    }

    /**
     * 선언된 길이만 믿으면 안 된다 — 청크 전송은 길이를 안 싣고({@code -1}), 실린 값이 사실이라는
     * 보장도 없다. 실제로 읽는 양도 끊어야 상한이 상한이다.
     */
    @Test
    void rejectsABodyThatExceedsTheLimitEvenWhenNoLengthWasDeclared() {
        HttpServletRequest request = request("empty");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentLengthLong()).thenReturn(-1L);   // 청크 전송
        stubBodyOf(request, 11 * 1024 * 1024);

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(gatewayService, never()).proxy(any(), anyString(), anyString(), any(), any());
    }

    /** 상한 안의 본문은 그대로 컨테이너로 간다 — 앱의 폼·등록이 계속 동작해야 한다. */
    @Test
    void stillProxiesABodyWithinTheLimit() {
        HttpServletRequest request = request("empty");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentLengthLong()).thenReturn(-1L);
        stubBodyOf(request, 64 * 1024);

        ResponseEntity<Resource> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gatewayService).proxy(any(), anyString(), anyString(), any(), any());
    }

    /** 지정한 바이트 수를 게으르게 내보내는 본문. 테스트가 10 MiB 배열을 미리 만들지 않게 한다. */
    private void stubBodyOf(HttpServletRequest request, int bytes) {
        ServletInputStream stream = new ServletInputStream() {
            private int remaining = bytes;

            @Override public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }

            @Override public boolean isFinished() {
                return remaining <= 0;
            }

            @Override public boolean isReady() {
                return true;
            }

            @Override public void setReadListener(ReadListener listener) { }
        };
        try {
            when(request.getInputStream()).thenReturn(stream);
        } catch (IOException ignored) {
            // 스텁은 실제 IO 를 하지 않는다 — checked 예외는 형식상일 뿐.
        }
    }

    private PreviewSessionInfo session() {
        return new PreviewSessionInfo(
                SESSION_ID, OWNER, 11L, null, null, "container-1", 32768,
                "https://qeploy.com/api/v1/previews/session-1/token-1/",
                LocalDateTime.now().plusMinutes(30));
    }

    /** Sec-Fetch-Dest 없는 요청 — curl 등 비브라우저. 탐색으로 간주되어 쿠키 게이트를 받는다. */
    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/previews/" + SESSION_ID + "/" + ACCESS_TOKEN + "/");
        // 프록시가 method·본문을 읽으므로 스텁한다(인가 통과 경로에서만 실제로 읽힘).
        when(request.getMethod()).thenReturn("GET");
        try {
            when(request.getInputStream()).thenReturn(emptyStream());
        } catch (IOException ignored) {
            // getInputStream 스텁은 실제 IO 를 하지 않는다 — checked 예외는 형식상일 뿐.
        }
        return request;
    }

    /** 본문 없는 ServletInputStream(빈 배열). 프록시가 readAllBytes() 로 읽어 빈 본문을 얻는다. */
    private ServletInputStream emptyStream() {
        ByteArrayInputStream backing = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override public int read() { return backing.read(); }
            @Override public boolean isFinished() { return backing.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
    }

    private HttpServletRequest request(String secFetchDest) {
        HttpServletRequest request = request();
        when(request.getHeader(PreviewGatewayController.SEC_FETCH_DEST)).thenReturn(secFetchDest);
        return request;
    }
}
