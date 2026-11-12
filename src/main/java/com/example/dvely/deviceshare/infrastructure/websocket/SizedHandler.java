package com.example.dvely.deviceshare.infrastructure.websocket;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * 연결마다 메시지 크기·유휴 상한을 둔다. 화면 한 장(최대 2MiB)이 들어가도록 바이너리는 3MiB, 텍스트는 64KiB.
 * 유휴 2분 — 30초 ping 의 pong 이 오지 않으면 닫힌다.
 */
class SizedHandler extends WebSocketHandlerDecorator {

    SizedHandler(WebSocketHandler delegate) {
        super(delegate);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setBinaryMessageSizeLimit(3 * 1024 * 1024);
        session.setTextMessageSizeLimit(64 * 1024);
        if (session instanceof NativeWebSocketSession nativeSession
                && nativeSession.getNativeSession() instanceof jakarta.websocket.Session ws) {
            ws.setMaxIdleTimeout(120_000);
            ws.setMaxBinaryMessageBufferSize(3 * 1024 * 1024);
            ws.setMaxTextMessageBufferSize(64 * 1024);
        }
        super.afterConnectionEstablished(session);
    }
}
