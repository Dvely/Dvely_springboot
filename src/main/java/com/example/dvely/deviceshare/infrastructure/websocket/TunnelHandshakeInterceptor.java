package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.application.service.ShareEndedException;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 기기 터널 핸드셰이크. 인증은 앞의 보안 체인({@code JwtAuthenticationFilter})이 {@code Authorization} 헤더로 했다.
 * 여기서는 링크 주인·상태·PAT 스코프·프로토콜 버전을 본다.
 *
 * <p>핸드셰이크는 GET 이라 읽기 전용 PAT 도 보안 체인을 지난다. 터널은 화면을 내보내고 입력을 받는 쓰기 동작이므로
 * WRITE 스코프를 여기서 요구한다.</p>
 */
@Component
@RequiredArgsConstructor
public class TunnelHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern PATH = Pattern.compile("^/api/v1/device-shares/(\\d+)/tunnel$");

    private final DeviceShareService service;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Matcher m = PATH.matcher(request.getURI().getPath());
        if (!m.matches()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        String query = request.getURI().getQuery();
        if (query == null || !query.matches("(^|.*&)v=1(&.*|$)")) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        boolean pat = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(a -> a.startsWith("SCOPE_"));
        boolean write = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("SCOPE_WRITE"::equals);
        if (pat && !write) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            DeviceShareLink link = service.requireTunnelAccess(userId, Long.parseLong(m.group(1)));
            attributes.put(DeviceTunnelHandler.ATTR_LINK, link);
            return true;
        } catch (NotFoundException | NumberFormatException e) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
        } catch (ShareEndedException e) {
            response.setStatusCode(HttpStatus.GONE);
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
