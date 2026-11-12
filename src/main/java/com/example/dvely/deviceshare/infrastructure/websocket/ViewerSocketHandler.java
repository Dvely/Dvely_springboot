package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 보는 사람 소켓. 첫 메시지는 {@code join} 이어야 한다(설계 §4.2). 내 제어 링크 비밀이 맞으면 주인으로 들어온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewerSocketHandler extends AbstractWebSocketHandler {

    static final String ATTR_LINK = "deviceShare.viewLink";
    private static final String ATTR_CHANNEL = "deviceShare.viewerChannel";
    private static final String ATTR_VIEWER_ID = "deviceShare.viewerId";

    private final ShareSessionRegistry registry;
    private final DeviceShareService service;
    private final ObjectMapper json;
    private final Map<String, WebSocketViewerChannel> open = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketViewerChannel channel = new WebSocketViewerChannel(session);
        session.getAttributes().put(ATTR_CHANNEL, channel);
        open.put(session.getId(), channel);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DeviceShareLink link = (DeviceShareLink) session.getAttributes().get(ATTR_LINK);
        WebSocketViewerChannel channel = (WebSocketViewerChannel) session.getAttributes().get(ATTR_CHANNEL);
        JsonNode node;
        try {
            node = json.readTree(message.getPayload());
        } catch (Exception e) {
            return;
        }
        String viewerId = (String) session.getAttributes().get(ATTR_VIEWER_ID);
        if (viewerId != null) {
            registry.onViewerMessage(link.getId(), viewerId, node);
            return;
        }
        if (!"join".equals(node.path("type").asText(""))) {
            channel.sendText("{\"type\":\"error\",\"message\":\"먼저 join 을 보내야 합니다.\"}");
            return;
        }
        boolean owner = service.isOwnerSecret(link, node.path("controlSecret").asText(null));
        Optional<String> joined = registry.join(link, channel, node.path("name").asText(""), owner);
        if (joined.isEmpty()) {
            channel.sendText("{\"type\":\"error\",\"message\":\"보는 사람이 너무 많아 들어갈 수 없습니다.\"}");
            channel.close("full");
            return;
        }
        session.getAttributes().put(ATTR_VIEWER_ID, joined.get());
        service.touchViewed(link.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 보는 사람은 바이너리를 보내지 않는다
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocketViewerChannel channel = open.remove(session.getId());
        if (channel != null) {
            channel.close("closed");
        }
        DeviceShareLink link = (DeviceShareLink) session.getAttributes().get(ATTR_LINK);
        String viewerId = (String) session.getAttributes().get(ATTR_VIEWER_ID);
        if (link != null && viewerId != null) {
            registry.leave(link.getId(), viewerId);
        }
    }

    void pingAll() {
        open.values().forEach(WebSocketViewerChannel::ping);
    }
}
