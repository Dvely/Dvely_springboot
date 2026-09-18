package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HTTP 헬스 프로브 — 앱이 뜬 채로 DB 등에 못 붙는 기능적 이상(/api/health 5xx)을 잡는다(배포 e2e 발견 #3).
 * 보수적으로 5xx 만 이상으로 보고, 그 외(2xx·404·무응답)는 TCP 판정에 맡긴다.
 */
class HttpHealthProbeTest {

    private HttpServer app;
    private HttpHealthProbe probe;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = app.getAddress().getPort();
        app.start();
        probe = new HttpHealthProbe();
        ReflectionTestUtils.setField(probe, "healthPath", "/api/health");
    }

    @AfterEach
    void tearDown() {
        app.stop(0);
    }

    private void serveHealth(int status) {
        app.createContext("/api/health", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void reportsUnhealthyWhenHealthEndpointReturns5xx() {
        serveHealth(503);   // 앱이 DB down 등을 5xx 로 보고

        assertThat(probe.reportsUnhealthy("127.0.0.1", port)).isTrue();
    }

    @Test
    void notUnhealthyWhenHealthEndpointReturns2xx() {
        serveHealth(200);

        assertThat(probe.reportsUnhealthy("127.0.0.1", port)).isFalse();
    }

    /** 헬스 엔드포인트가 없으면(404) 이상으로 보지 않는다 — TCP 판정 유지(엔드포인트 없는 앱 기존 동작 보존). */
    @Test
    void notUnhealthyWhenNoHealthEndpoint() {
        // /api/health 핸들러를 등록하지 않음 → 기본 404

        assertThat(probe.reportsUnhealthy("127.0.0.1", port)).isFalse();
    }

    /** 포트에 아무도 없으면(연결 거부) 이상으로 보지 않는다 — 그건 TCP 체커가 프로세스 死로 잡는 몫이다. */
    @Test
    void notUnhealthyWhenConnectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket probeSocket = new ServerSocket(0)) {
            closedPort = probeSocket.getLocalPort();
        }   // 닫힘

        assertThat(probe.reportsUnhealthy("127.0.0.1", closedPort)).isFalse();
    }
}
