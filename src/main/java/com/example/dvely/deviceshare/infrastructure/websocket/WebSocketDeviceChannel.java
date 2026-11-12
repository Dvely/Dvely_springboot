package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.session.DeviceChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 기기 터널 연결. 기기로 가는 것은 작은 제어 프레임뿐이라 Spring 의 동시 전송 데코레이터(보내기 10초·버퍼 4MiB)로
 * 충분하다. 넘치면 데코레이터가 연결을 닫는다(설계 §4.4).
 */
@Slf4j
class WebSocketDeviceChannel implements DeviceChannel {

    private final ConcurrentWebSocketSessionDecorator session;

    WebSocketDeviceChannel(WebSocketSession raw) {
        this.session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 4 * 1024 * 1024);
    }

    @Override
    public void sendBinary(ByteBuffer frame) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(frame));
        } catch (IOException | RuntimeException e) {
            log.debug("[DeviceShare] 기기로 보내지 못함: {}", e.getMessage());
            close("send-failed");
        }
    }

    @Override
    public void close(String reason) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL.withReason(reason));
            }
        } catch (IOException ignored) {
            // 이미 닫히는 중
        }
    }

    void ping() throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new org.springframework.web.socket.PingMessage());
        }
    }
}
