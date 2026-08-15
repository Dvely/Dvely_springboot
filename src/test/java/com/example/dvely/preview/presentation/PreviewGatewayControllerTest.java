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
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        when(gatewayService.proxy(any(), anyString(), anyString(), any()))
                .thenReturn(ResponseEntity.ok("body".getBytes()));
    }

    @Test
    void rejectsARequestWithoutTheOwnershipCookieEvenWhenTheUrlIsCorrect() {
        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(gatewayService, never()).proxy(any(), anyString(), anyString(), any());
    }

    @Test
    void rejectsACookieIssuedForAnotherSession() {
        String foreign = accessCookies.issue("other-session", OWNER, Duration.ofMinutes(30));

        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, foreign, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsACookieIssuedForAnotherUser() {
        String foreign = accessCookies.issue(SESSION_ID, 99L, Duration.ofMinutes(30));

        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, foreign, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void servesTheOwnerWhoPresentsTheIssuedCookie() {
        String cookie = accessCookies.issue(SESSION_ID, OWNER, Duration.ofMinutes(30));

        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, cookie, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gatewayService).proxy(any(), anyString(), anyString(), any());
    }

    /** 세션 자체가 없으면(만료·오토큰) 쿠키 이전에 404다 — 존재 여부를 인가로 흘리지 않는다. */
    @Test
    void keepsReturningNotFoundForAnUnknownSession() {
        when(sessionService.resolveGateway(SESSION_ID, "wrong")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, "wrong", null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * sandbox(불투명 오리진) 문서가 끌어오는 서브리소스에는 브라우저가 쿠키를 실어주지 않는다
     * (Issue #108 — SameSite 와 무관하게 module script 는 credentials 자체를 보내지 않는다).
     * 회전 accessToken 이 든 URL 자체가 자격이므로 쿠키 없이 통과해야 프리뷰가 백지가 되지 않는다.
     */
    @Test
    void servesSubresourceRequestsWithoutTheCookie() {
        ResponseEntity<byte[]> script = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("script"));
        ResponseEntity<byte[]> fetch = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request("empty"));

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

        ResponseEntity<byte[]> response = controller.proxy(SESSION_ID, ACCESS_TOKEN, null, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
        return request;
    }

    private HttpServletRequest request(String secFetchDest) {
        HttpServletRequest request = request();
        when(request.getHeader(PreviewGatewayController.SEC_FETCH_DEST)).thenReturn(secFetchDest);
        return request;
    }
}
