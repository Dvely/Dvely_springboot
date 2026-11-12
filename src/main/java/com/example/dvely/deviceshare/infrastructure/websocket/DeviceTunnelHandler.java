package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.session.ShareFrames;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** 기기(CLI) 터널. 핸드셰이크에서 링크 주인·상태를 확인했다({@link TunnelHandshakeInterceptor}). */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTunnelHandler extends BinaryWebSocketHandler {

    static final String ATTR_LINK = "deviceShare.link";
    private static final String ATTR_CHANNEL = "deviceShare.deviceChannel";

    private final ShareSessionRegistry registry;
    private final Map<String, WebSocketDeviceChannel> open = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        DeviceShareLink link = (DeviceShareLink) session.getAttributes().get(ATTR_LINK);
        WebSocketDeviceChannel channel = new WebSocketDeviceChannel(session);
        session.getAttributes().put(ATTR_CHANNEL, channel);
        if (registry.attachDevice(link, channel).isEmpty()) {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("서버의 공유 자리가 모두 찼습니다"));
            return;
        }
        open.put(session.getId(), channel);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        DeviceShareLink link = (DeviceShareLink) session.getAttributes().get(ATTR_LINK);
        WebSocketDeviceChannel channel = (WebSocketDeviceChannel) session.getAttributes().get(ATTR_CHANNEL);
        ShareFrames.Frame frame;
        try {
            frame = ShareFrames.decode(message.getPayload());
        } catch (IllegalArgumentException e) {
            log.debug("[DeviceShare] 잘못된 프레임 share={}: {}", link.getId(), e.getMessage());
            return;
        }
        registry.onDeviceFrame(link.getId(), channel, frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        open.remove(session.getId());
        DeviceShareLink link = (DeviceShareLink) session.getAttributes().get(ATTR_LINK);
        WebSocketDeviceChannel channel = (WebSocketDeviceChannel) session.getAttributes().get(ATTR_CHANNEL);
        if (link != null && channel != null) {
            registry.detachDevice(link.getId(), channel);
        }
    }

    /** 연결이 살아 있는지 확인한다. 응답(pong)이 없으면 컨테이너의 유휴 시간 상한이 닫는다. */
    void pingAll() {
        for (WebSocketDeviceChannel channel : open.values()) {
            try {
                channel.ping();
            } catch (Exception e) {
                channel.close("ping-failed");
            }
        }
    }
}
