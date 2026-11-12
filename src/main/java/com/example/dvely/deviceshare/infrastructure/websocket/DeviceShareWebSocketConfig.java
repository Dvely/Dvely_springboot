package com.example.dvely.deviceshare.infrastructure.websocket;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 내 기기 프리뷰 공유의 WebSocket 두 개(#652).
 *
 * <ul>
 *   <li>{@code /api/v1/device-shares/{id}/tunnel} — 기기(CLI). PAT 를 {@code Authorization} 헤더로 싣는다.</li>
 *   <li>{@code /__qeploy/ws} — 보는 사람. 공유 오리진에서만 받는다.</li>
 * </ul>
 *
 * <p>메시지 크기·유휴 시간은 컨테이너 전역 빈({@code ServletServerContainerFactoryBean}) 대신 연결마다 정한다. 그 빈은
 * 목 서블릿 환경(MockMvc 테스트)에서 기동을 깨뜨린다. 30초마다 ping 을 보내 끊긴 연결이 유휴 상한으로 닫히게 한다.</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class DeviceShareWebSocketConfig implements WebSocketConfigurer {

    private final DeviceTunnelHandler tunnelHandler;
    private final TunnelHandshakeInterceptor tunnelInterceptor;
    private final ViewerSocketHandler viewerHandler;
    private final ViewerHandshakeInterceptor viewerInterceptor;
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("device-share-ping").daemon(true).factory());

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SizedHandler(tunnelHandler), "/api/v1/device-shares/*/tunnel")
                .addInterceptors(tunnelInterceptor);
        registry.addHandler(new SizedHandler(viewerHandler), "/__qeploy/ws")
                .addInterceptors(viewerInterceptor);
        pinger.scheduleWithFixedDelay(() -> {
            tunnelHandler.pingAll();
            viewerHandler.pingAll();
        }, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        pinger.shutdownNow();
    }
}
