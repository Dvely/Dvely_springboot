package com.example.dvely.preview.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SSE 스트리밍 프록시 라우팅 — Spring 의 {@code produces} 협상이 요청을 올바른 핸들러로 보내는지 고정한다.
 *
 * <p>이 검증은 컨트롤러 메서드를 직접 부르는 {@link PreviewGatewayControllerTest} 로는 못 잡는다 —
 * 어느 핸들러가 뽑히는지는 Spring MVC 의 매핑 협상 결과라서다. 특히 <b>보안상 결정적</b>인 것은,
 * 브라우저 문서 로드({@code Accept: text/html,...,*&#47;*})가 SSE 핸들러로 새면 shim 주입·sandbox CSP 가
 * 붙는 버퍼링 경로를 건너뛰어 격리가 통째로 빠진다는 점이다. 그 오배치가 없음을 여기서 고정한다.</p>
 */
class PreviewGatewaySseRoutingTest {

    private static final String SID = "session-1";
    private static final String TOKEN = "token-1";
    private static final String BASE = "/api/v1/previews/" + SID + "/" + TOKEN + "/stream";

    private PreviewSessionService sessionService;
    private PreviewGatewayService gatewayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(PreviewSessionService.class);
        gatewayService = mock(PreviewGatewayService.class);
        PreviewAccessCookies cookies = new PreviewAccessCookies(
                new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L));
        PreviewProperties properties = new PreviewProperties();
        // 라우팅만 검증한다 — 소유권 쿠키 게이트가 끼어들지 않게 끈다.
        properties.setRequireAccessCookie(false);

        when(sessionService.resolveGateway(SID, TOKEN)).thenReturn(Optional.of(session()));
        when(gatewayService.proxy(anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok("doc".getBytes()));
        when(gatewayService.proxyEventStream(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(out -> { }));   // no-op 스트림

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PreviewGatewayController(sessionService, gatewayService, cookies, properties))
                .build();
    }

    /** EventSource 는 Accept: text/event-stream 을 보낸다 → 스트리밍 핸들러로. 버퍼링 proxy 는 안 불린다. */
    @Test
    void routesEventStreamAcceptToTheStreamingHandler() throws Exception {
        mockMvc.perform(get(BASE).accept(MediaType.TEXT_EVENT_STREAM));

        verify(gatewayService).proxyEventStream(any(), anyString(), any(), any());
        verify(gatewayService, never()).proxy(anyString(), any(), anyString(), any(), any(), any(), any());
    }

    /**
     * 보안상 결정적: 브라우저 문서 로드는 Accept 에 *&#47;* 가 들어 있어 SSE produces 와도 호환되지만,
     * 반드시 버퍼링 proxy(shim 주입·sandbox CSP) 로 가야 한다 — 스트리밍 핸들러로 새면 격리가 빠진다.
     */
    @Test
    void routesBrowserDocumentAcceptToTheBufferingProxyNotTheStreamingHandler() throws Exception {
        mockMvc.perform(get(BASE)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"));

        verify(gatewayService).proxy(anyString(), any(), anyString(), any(), any(), any(), any());
        verify(gatewayService, never()).proxyEventStream(any(), any(), any(), any());
    }

    private PreviewSessionInfo session() {
        return new PreviewSessionInfo(
                SID, 7L, 11L, null, null, "container-1", 32768,
                "https://qeploy.com/api/v1/previews/" + SID + "/" + TOKEN + "/",
                LocalDateTime.now().plusMinutes(30));
    }
}
