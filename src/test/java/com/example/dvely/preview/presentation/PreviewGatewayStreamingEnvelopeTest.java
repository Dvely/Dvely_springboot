package com.example.dvely.preview.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.application.service.PreviewGatewayService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 스트리밍 봉투가 <b>Spring MVC 를 통과한 뒤에도</b> 예전과 같은 응답을 내는지 고정한다
 * (Issue #342, 7-3).
 *
 * <p>이 검증은 서비스 단위 테스트로 못 잡는다. {@code ResponseEntity<Resource>} 는 Spring 이
 * {@code AbstractMessageConverterMethodProcessor} 에서 특별 취급하기 때문이다 — Resource 를
 * 돌려주면 {@code Accept-Ranges: bytes} 를 달고, {@code Range} 요청이 오면 본문을
 * {@code ResourceRegion} 으로 바꿔 206 으로 내보낸다. 그 경로는 {@code contentLength()} 를 요구하는데
 * 스트림 기반 자원은 그것을 세느라 스트림을 소진해버려 본문이 깨진다.</p>
 *
 * <p><b>{@code InputStreamResource} 만은 그 특별 취급에서 제외</b>돼 있어(그 클래스와 정확히 일치할
 * 때만) 우리 자산 응답은 안전하다. 하지만 그것은 <b>봉투 타입에 달린 성질</b>이다 — 누군가
 * 스트리밍 봉투를 다른 {@code Resource} 구현으로 바꾸면 프리뷰 앱의 {@code <video>}·PDF 같은 Range
 * 요청이 조용히 깨진다. 그래서 실제 MVC 를 태워 그 계약을 고정한다.</p>
 */
class PreviewGatewayStreamingEnvelopeTest {

    private static final String SID = "session-1";
    private static final String TOKEN = "token-1";
    private static final String ASSET = "/api/v1/previews/" + SID + "/" + TOKEN + "/assets/app.js";
    private static final String SCRIPT = "console.log(1)";

    private HttpServer container;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        container = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        container.createContext("/assets/app.js", exchange -> {
            byte[] body = SCRIPT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/javascript");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        container.start();

        PreviewSessionService sessionService = mock(PreviewSessionService.class);
        when(sessionService.resolveGateway(SID, TOKEN)).thenReturn(Optional.of(session()));
        PreviewProperties properties = new PreviewProperties();
        // 봉투만 본다 — 인가는 PreviewGatewayControllerTest 가 따로 고정한다.
        properties.setRequireAccessCookie(false);

        mockMvc = MockMvcBuilders.standaloneSetup(new PreviewGatewayController(
                        sessionService,
                        new PreviewGatewayService("'self'", false, id -> false),
                        new PreviewAccessCookies(
                                new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L)),
                        properties))
                .build();
    }

    @AfterEach
    void tearDown() {
        container.stop(0);
    }

    /** 평범한 요청은 자산 전체를 200 으로 받는다. */
    @Test
    void streamsTheWholeAssetThroughMvc() throws Exception {
        mockMvc.perform(get(ASSET).header(PreviewGatewayController.SEC_FETCH_DEST, "script"))
                .andExpect(status().isOk())
                .andExpect(content().string(SCRIPT))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/javascript"));
    }

    /**
     * Range 요청도 예전(byte[] 봉투)과 똑같이 전체 본문 + 200 이어야 한다. 206 이 나오거나 본문이
     * 비면, 봉투가 Spring 의 Range 특별 취급에 걸린 것이고 프리뷰의 미디어 재생이 깨진다.
     */
    @Test
    void doesNotLetSpringTurnAStreamedAssetIntoARangeResponse() throws Exception {
        mockMvc.perform(get(ASSET)
                        .header(PreviewGatewayController.SEC_FETCH_DEST, "script")
                        .header(HttpHeaders.RANGE, "bytes=0-3"))
                .andExpect(status().isOk())
                .andExpect(content().string(SCRIPT))
                .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_RANGES));
    }

    private PreviewSessionInfo session() {
        return new PreviewSessionInfo(
                SID, 7L, 11L, null, null, "container-1", container.getAddress().getPort(),
                "https://qeploy.com/api/v1/previews/" + SID + "/" + TOKEN + "/",
                LocalDateTime.now().plusMinutes(30));
    }
}
