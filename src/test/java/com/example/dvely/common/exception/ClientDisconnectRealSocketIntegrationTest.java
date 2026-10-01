package com.example.dvely.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code #423} 을 <b>진짜 끊김</b>으로 확인한다 — 합성 예외가 아니라 실제 Tomcat 에 실제 소켓을
 * 붙이고 끊는다.
 *
 * <h2>왜 단위 테스트로 부족한가</h2>
 * {@code GlobalExceptionHandlerTest} 는 {@link org.springframework.web.context.request.async
 * .AsyncRequestNotUsableException} 을 <b>직접 던져</b> advice 가 가로채는지만 본다. 그것이 증명하지
 * 못하는 것이 하나 남는다 — <b>이미 죽은 응답에 핸들러가 아무것도 쓰지 않는가.</b> 핸들러 반환형을
 * {@code void} 로 둔 이유가 그것이고, 틀렸다면 2차 실패가 또 다른 ERROR 로 나타난다. 죽은 응답은
 * 목으로 만들 수 없어서 여기까지 와야 한다.
 *
 * <h2>이 테스트가 "아무것도 하지 않고 통과"하지 않도록</h2>
 * "ERROR 가 없다"만 보면 <b>끊김이 아예 일어나지 않아도 통과한다</b>(dev 에서 실제로 그 함정에
 * 빠졌다 — 기저율이 11시간에 2건이라 부재가 증거가 되지 못했다). 그래서 순서를 뒤집어 <b>끊김이
 * 실제로 감지됐음을 먼저 단정</b>한다:
 * <ol>
 *   <li>쓰기 스레드가 {@code IOException} 을 받았다 — 서버가 끊김을 알아챘다는 증거
 *   <li>advice 가 그것을 받아 DEBUG 한 줄을 남겼다 — 예외가 핸들러까지 도달했다는 증거
 *   <li><b>그런 다음</b> ERROR 가 하나도 없다
 * </ol>
 * 1·2 가 성립하지 않으면 3 은 의미가 없으므로, 실패 메시지가 그것을 구별해 말한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientDisconnectRealSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void 진짜_소켓을_끊어도_ERROR_로_남지_않는다() throws Exception {
        AbortStreamController.reset();

        Logger adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level original = adviceLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adviceLogger.addAppender(appender);
        // 조용해진 줄이 DEBUG 라, 레벨을 내리지 않으면 "핸들러가 받았다" 를 볼 수 없다.
        adviceLogger.setLevel(Level.DEBUG);
        try {
            openStreamThenAbort();

            assertThat(AbortStreamController.writerFinished().await(15, TimeUnit.SECONDS))
                    .as("쓰기 스레드가 끝나지 않았다 — 끊김이 감지되지 않았으므로 이 테스트는 "
                            + "아무것도 검증하지 못한다")
                    .isTrue();
            assertThat(AbortStreamController.sendFailure())
                    .as("send() 가 실패하지 않았다 — 서버가 끊김을 알아채지 못했다")
                    .isNotNull();

            List<ILoggingEvent> logs = awaitAdviceEvent(appender);

            assertThat(logs)
                    .as("advice 가 끊김을 받지 못했다 — 예외가 핸들러까지 오지 않았다면 "
                            + "'ERROR 없음' 은 증거가 아니다")
                    .anyMatch(e -> e.getLevel() == Level.DEBUG
                            && e.getFormattedMessage().contains("연결을 끊었습니다"));

            // 본 단정. void 반환이 2차 실패를 만들지 않는지까지 여기서만 드러난다.
            assertThat(logs)
                    .as("클라이언트 끊김이 ERROR 로 기록됐다(#423 회귀). 2차 실패라면 메시지가 "
                            + "'연결을 끊었습니다' 가 아닌 다른 것이다")
                    .noneMatch(e -> e.getLevel() == Level.ERROR);
        } finally {
            adviceLogger.setLevel(original);
            adviceLogger.detachAppender(appender);
        }
    }

    /**
     * 응답이 <b>실제로 시작된 뒤</b> 끊는다. 헤더를 읽기 전에 끊으면 서버가 쓰기를 시도한 적이
     * 없어 이 예외가 발생하지 않는다. {@code SO_LINGER=0} 은 FIN 대신 RST 를 보내 — 다음 쓰기가
     * 반드시 실패하게 만든다(FIN 만 보내면 커널 버퍼에 따라 몇 번 더 성공할 수 있다).
     */
    private void openStreamThenAbort() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoLinger(true, 0);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /it/abort-stream HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder seen = new StringBuilder();
            byte[] buf = new byte[256];
            // 스트림 본문의 첫 바이트까지 본다 — 그래야 서버가 쓰기를 시작했음이 확실하다.
            while (!seen.toString().contains(AbortStreamController.FIRST_CHUNK)) {
                int n = in.read(buf);
                if (n < 0) {
                    throw new IllegalStateException("서버가 스트림을 시작하기 전에 닫았다: " + seen);
                }
                seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            assertThat(seen.toString()).startsWith("HTTP/1.1 200");
        }
    }

    /** advice 는 컨테이너 스레드에서 비동기 디스패치로 호출되므로, 기록이 올라올 때까지 짧게 기다린다. */
    private List<ILoggingEvent> awaitAdviceEvent(ListAppender<ILoggingEvent> appender) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (!appender.list.isEmpty()) {
                Thread.sleep(200);   // 뒤따르는 줄(있다면 ERROR)도 함께 담기게 조금 더 기다린다
                break;
            }
            Thread.sleep(100);
        }
        return List.copyOf(appender.list);
    }

    @TestConfiguration
    static class ItSecurity {

        /**
         * {@code /it/**} 만 담당하는 별도 체인. 운영 체인을 건드리지 않고 테스트 경로만 열려면
         * 체인을 하나 더 두는 것이 맞다 — 운영 설정을 테스트용으로 고치면 그 설정이 더는
         * 검증 대상이 아니게 된다.
         */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain itChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/it/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }

        @Bean
        AbortStreamController abortStreamController() {
            return new AbortStreamController();
        }
    }

    /**
     * 실제 {@code AgentEventStreamService} 와 <b>같은 실패 경로</b>를 쓴다 — 쓰기 스레드가
     * {@code IOException} 을 받으면 {@code completeWithError} 로 넘긴다. 그래야 Spring 이 비동기
     * 디스패치로 advice 를 부르고, dev 에서 관측된 것과 같은 모양이 된다.
     */
    @RestController
    static class AbortStreamController {

        static final String FIRST_CHUNK = ":qeploy-it-open";

        private static final AtomicReference<Throwable> SEND_FAILURE = new AtomicReference<>();
        private static final AtomicReference<CountDownLatch> WRITER_DONE =
                new AtomicReference<>(new CountDownLatch(1));

        static void reset() {
            SEND_FAILURE.set(null);
            WRITER_DONE.set(new CountDownLatch(1));
        }

        static Throwable sendFailure() {
            return SEND_FAILURE.get();
        }

        static CountDownLatch writerFinished() {
            return WRITER_DONE.get();
        }

        @GetMapping(value = "/it/abort-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            SseEmitter emitter = new SseEmitter(60_000L);
            CountDownLatch done = WRITER_DONE.get();
            Thread.ofVirtual().name("it-sse-writer").start(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("qeploy-it-open"));
                    for (int i = 0; i < 300; i++) {
                        Thread.sleep(50);
                        emitter.send(SseEmitter.event().comment("hb-" + i));
                    }
                    emitter.complete();
                } catch (Exception e) {
                    SEND_FAILURE.set(e);
                    emitter.completeWithError(e);
                } finally {
                    done.countDown();
                }
            });
            return emitter;
        }
    }
}
