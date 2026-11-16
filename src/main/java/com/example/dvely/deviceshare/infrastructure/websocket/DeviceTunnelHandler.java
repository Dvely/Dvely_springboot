package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.service.ShareFeedbackService;
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
    private final ShareFeedbackService feedback;
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
        if (frame.kind() == ShareFrames.FEEDBACK_DECISION) {
            // 받기·무시는 저장이 필요해 세션 밖에서(설계 §14). DB 오류로 터널을 끊지 않는다
            try {
                feedback.onDeviceDecision(link, frame);
            } catch (RuntimeException e) {
                log.warn("[DeviceShare] 피드백 받기·무시 처리 실패 share={}: {}", link.getId(), e.getMessage());
            }
            return;
        }
        registry.onDeviceFrame(link.getId(), channel, frame);
        if (frame.kind() == ShareFrames.HELLO) {
            try {
                feedback.renotifyPending(link);
            } catch (RuntimeException e) {
                log.warn("[DeviceShare] 받기 전 피드백 다시 알리기 실패 share={}: {}", link.getId(), e.getMessage());
            }
        }
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
