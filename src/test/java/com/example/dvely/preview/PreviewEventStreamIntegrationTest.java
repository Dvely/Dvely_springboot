package com.example.dvely.preview;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dvely.common.exception.GlobalExceptionHandler;
import com.example.dvely.preview.application.service.PreviewEventStreamLimiter;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * 프리뷰 SSE 프록시를 실제 톰캣과 실제 소켓으로 본다(#624).
 *
 * <p>고치기 전 같은 하네스로 잰 값: 스트림이 <b>30.6초</b>(톰캣 기본 비동기 타임아웃)에 끊겼고, 그때마다
 * {@code GlobalExceptionHandler} 가 {@code AsyncRequestTimeoutException} 을 스택과 함께 ERROR 로 남기고
 * {@code DefaultHandlerExceptionResolver} 가 WARN 두 줄을 더 남겼다. 여기서는 타임아웃을 2초로 줄여, 그 값이 실제로
 * 걸리는지(30초가 아니라)와 끊김이 조용한지를 본다.</p>
 *
 * <p>컨테이너 대신 이 테스트가 세운 SSE 서버를 세션의 {@code container_ip}("127.0.0.1:포트")로 가리킨다. 이벤트소스
 * 요청({@code Sec-Fetch-Dest: empty})은 접근 쿠키 없이 URL 토큰만으로 통과한다(#108).</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.mvc.async.request-timeout=2s",
                "qeploy.preview.event-stream.max-concurrent=1"
        })
class PreviewEventStreamIntegrationTest {

    private static final String MARK = "sse624-";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PreviewEventStreamLimiter limiter;
    @Autowired private RequestMappingHandlerAdapter handlerAdapter;
    @Autowired @Qualifier("mvcAsyncExecutor") private SimpleAsyncTaskExecutor mvcAsyncExecutor;

    private HttpServer upstream;
    private final AtomicInteger upstreamConnections = new AtomicInteger();
    private final AtomicInteger upstreamOpen = new AtomicInteger();
    private ListAppender<ILoggingEvent> logs;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/events", exchange -> {
            upstreamConnections.incrementAndGet();
            upstreamOpen.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // 스스로 닫지 않는 업스트림 — 프록시가 닫아야만 끝난다
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; ; i++) {
                    out.write(("data: tick " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
                // 프록시가 연결을 닫았다
            } finally {
                upstreamOpen.decrementAndGet();
            }
        });
        upstream.start();

        logs = new ListAppender<>();
        logs.start();
        logger(GlobalExceptionHandler.class).addAppender(logs);
        logger(DefaultHandlerExceptionResolver.class).addAppender(logs);

        sessionId = UUID.randomUUID().toString();
        String github = MARK + UUID.randomUUID();
        jdbc.update("insert into users (github_user_id, user_name) values (?, ?)", github, MARK + "u");
        Long userId = jdbc.queryForObject("select user_id from users where github_user_id = ?", Long.class, github);
        jdbc.update("""
                insert into preview_sessions (preview_session_id, access_token, user_id, container_id, container_ip,
                    status, public_url, expires_at, last_accessed_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, now())
                """, sessionId, token(), userId, MARK + "c", "127.0.0.1:" + upstream.getAddress().getPort(),
                "http://preview/", LocalDateTime.now().plusMinutes(30));
    }

    @AfterEach
    void tearDown() {
        logger(GlobalExceptionHandler.class).detachAppender(logs);
        logger(DefaultHandlerExceptionResolver.class).detachAppender(logs);
        upstream.stop(0);
        jdbc.update("delete from preview_sessions where preview_session_id = ?", sessionId);
        jdbc.update("delete from users where github_user_id like ?", MARK + "%");
    }

    /**
     * 이벤트가 생기는 대로 브라우저에 닿는다. 고치기 전에는 스트리밍 본문의 {@code out.flush()} 가 Spring Framework 7.0.6 부터
     * 아무것도 하지 않아, 0.2초 간격 이벤트가 스트림이 끝날 때(그때는 30초) 응답 헤더와 함께 한 덩어리로 도착했다.
     */
    @Test
    void eventsReachTheClientAsTheyHappenNotWhenTheStreamEnds() throws Exception {
        try (Socket socket = open()) {
            long start = System.nanoTime();
            InputStream in = socket.getInputStream();
            StringBuilder seen = new StringBuilder();
            byte[] buffer = new byte[1024];
            int reads = 0;
            while (seen.toString().split("data: tick", -1).length - 1 < 3) {
                int read = in.read(buffer);
                if (read < 0) {
                    throw new AssertionError("이벤트 3개가 오기 전에 닫혔다: " + seen);
                }
                reads++;
                seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 타임아웃(2초) 전에, 여러 번에 나눠 왔다
            assertThat(elapsedMs).isLessThan(1_500L);
            assertThat(reads).isGreaterThan(1);
        }
    }

    @Test
    void aStreamEndsAtTheConfiguredTimeoutQuietlyAndClosesTheContainerSide() throws Exception {
        long start = System.nanoTime();
        Stream stream = readToEnd(Duration.ofSeconds(20));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(stream.statusLine()).startsWith("HTTP/1.1 200");
        assertThat(stream.ticks()).as("이벤트가 흘렀다").isGreaterThanOrEqualTo(3);
        // 설정한 2초에 끝났다 — 톰캣 기본 30초가 아니다
        assertThat(elapsedMs).isBetween(1_500L, 10_000L);

        awaitZero(upstreamOpen, "업스트림(컨테이너 쪽) 연결이 닫히지 않았다 — 인터럽트가 읽기를 깨우지 못했다");
        awaitZero(() -> limiter.inUse(), "스트림 자리가 돌아오지 않았다");
        // 타임아웃은 정상 종료다. 고치기 전에는 ERROR 1 + WARN 2 였다.
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void aStreamOverTheLimitGets503WithoutTouchingTheContainer() throws Exception {
        try (Socket first = open()) {
            readUntilTick(first);
            assertThat(limiter.inUse()).isEqualTo(1);

            Stream second = readToEnd(Duration.ofSeconds(10));

            assertThat(second.statusLine()).startsWith("HTTP/1.1 503");
            assertThat(upstreamConnections).as("두 번째는 컨테이너에 연결하지 않았다").hasValue(1);
        }
        // 첫 스트림이 끝나면(클라이언트가 떠났거나 타임아웃) 다음 것이 들어간다
        awaitZero(() -> limiter.inUse(), "첫 스트림의 자리가 돌아오지 않았다");
        assertThat(readToEnd(Duration.ofSeconds(20)).statusLine()).startsWith("HTTP/1.1 200");
        assertThat(upstreamConnections).hasValue(2);
    }

    @Test
    void mvcAsyncWorkRunsOnTheBoundedVirtualThreadExecutor() throws Exception {
        // 고치기 전에는 MVC 기본값 MvcAsync — 작업마다 플랫폼 스레드를 새로 만들고 상한이 없다
        Field field = RequestMappingHandlerAdapter.class.getDeclaredField("taskExecutor");
        field.setAccessible(true);
        assertThat(field.get(handlerAdapter)).isSameAs(mvcAsyncExecutor);

        Future<Thread> thread = ((AsyncTaskExecutor) mvcAsyncExecutor).submit(Thread::currentThread);
        assertThat(thread.get(5, TimeUnit.SECONDS).isVirtual()).isTrue();
        assertThat(thread.get().getName()).startsWith("mvc-async-");
        assertThat(mvcAsyncExecutor.getConcurrencyLimit()).isEqualTo(200);
    }

    // ── 하네스 ─────────────────────────────

    private record Stream(String statusLine, int ticks) {}

    private Socket open() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/v1/previews/" + sessionId + "/" + token() + "/events HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Sec-Fetch-Dest: empty\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return socket;
    }

    private Stream readToEnd(Duration limit) throws Exception {
        try (Socket socket = open()) {
            InputStream in = socket.getInputStream();
            StringBuilder seen = new StringBuilder();
            byte[] buffer = new byte[1024];
            long deadline = System.nanoTime() + limit.toNanos();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("스트림이 " + limit + " 안에 끝나지 않았다");
                }
            }
            String text = seen.toString();
            return new Stream(text.lines().findFirst().orElse(""), text.split("data: tick", -1).length - 1);
        }
    }

    private void readUntilTick(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        StringBuilder seen = new StringBuilder();
        byte[] buffer = new byte[256];
        while (!seen.toString().contains("data: tick")) {
            int read = in.read(buffer);
            if (read < 0) {
                throw new AssertionError("첫 이벤트 전에 닫혔다: " + seen);
            }
            seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
    }

    private void awaitZero(AtomicInteger value, String message) throws InterruptedException {
        awaitZero(value::get, message);
    }

    private void awaitZero(java.util.function.IntSupplier value, String message) throws InterruptedException {
        for (int i = 0; i < 100 && value.getAsInt() != 0; i++) {
            Thread.sleep(100);
        }
        assertThat(value.getAsInt()).as(message).isZero();
    }

    private String token() {
        return sessionId.replace("-", "");
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }
}
