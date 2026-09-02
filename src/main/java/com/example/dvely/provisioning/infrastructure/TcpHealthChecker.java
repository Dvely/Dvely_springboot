package com.example.dvely.provisioning.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.stereotype.Component;

/**
 * 앱 포트가 실제로 응답하는지 TCP 연결로만 확인한다. 사용자의 앱이 어떤 헬스 엔드포인트를 가졌는지
 * 알 수 없으므로(/health 유무를 가정하지 않는다), 포트가 열렸는지만 본다 — 프리뷰의 awaitPortReady 와
 * 같은 원칙. 인스턴스가 running 이어도 java 기동 전까지는 포트가 안 열리므로, 이게 통과해야 RUNNING.
 */
@Component
public class TcpHealthChecker {

    private static final int CONNECT_TIMEOUT_MS = 2000;

    public boolean isHealthy(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
