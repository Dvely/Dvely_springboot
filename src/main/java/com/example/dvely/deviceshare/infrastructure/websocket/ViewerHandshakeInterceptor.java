package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.infrastructure.web.ShareHost;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 보는 사람 소켓 핸드셰이크. 공유 오리진(<토큰>.share.…)으로 온 것만 받는다. API 호스트로 오면 404 다.
 * 출처 확인은 Spring 의 기본(같은 오리진만)에 맡긴다 — 뷰어 페이지와 소켓이 같은 공유 오리진에 있다.
 */
@Component
@RequiredArgsConstructor
public class ViewerHandshakeInterceptor implements HandshakeInterceptor {

    private final DeviceShareService service;
    private final ShareHost shareHost;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String host = request.getHeaders().getHost() == null ? null : request.getHeaders().getHost().getHostString();
        Optional<String> token = shareHost.tokenOf(host);
        if (token.isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        Optional<DeviceShareLink> link = service.findByViewToken(token.get());
        if (link.isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!link.get().isActive(service.now())) {
            response.setStatusCode(HttpStatus.GONE);
            return false;
        }
        attributes.put(ViewerSocketHandler.ATTR_LINK, link.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
