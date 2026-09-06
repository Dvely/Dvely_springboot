package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * 도달 게이트는 ACTIVE 직전 프리뷰 앱이 게이트웨이 경유로 응답하는지 확인해 첫 로드 503(깨진 이미지)을 막는다.
 */
class PreviewReadinessProbeTest {

    @Test
    void returnsTrueOnceTheAppResponds() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);   // 본문 없는 200 — 응답이 오는 것만으로 도달로 본다
            ex.close();
        });
        server.start();
        try {
            assertThat(new PreviewReadinessProbe(5, 20).awaitReachable(server.getAddress().getPort())).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsFalseWhenNothingListensWithinBudget() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }   // 닫힘 — 아무도 리슨하지 않는다
        // 작은 예산(3회×10ms)으로 빠르게 도달 불가 확인. best-effort 이므로 false 를 돌려주고 호출자가 진행한다.
        assertThat(new PreviewReadinessProbe(3, 10).awaitReachable(closedPort)).isFalse();
    }
}
