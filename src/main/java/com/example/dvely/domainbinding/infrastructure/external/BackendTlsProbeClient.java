package com.example.dvely.domainbinding.infrastructure.external;

import com.example.dvely.domainbinding.application.port.out.BackendTlsProbePort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link BackendTlsProbePort} 구현 — JDK HttpClient 로 {@code https://hostname/} 에 GET 을 날려 유효한
 * 인증서로 응답하는지 확인한다. 핸드셰이크가 성공하면(어떤 HTTP 상태든) 신뢰 스토어로 검증된 진짜
 * 인증서라는 뜻이라 true. 자기서명·미발급·미연결이면 예외가 나 false. 리다이렉트는 따라가지 않는다
 * (핸드셰이크만 확인하면 되고, Caddy 의 http→https 308 을 https 로 오해하지 않게).
 */
@Slf4j
@Component
public class BackendTlsProbeClient implements BackendTlsProbePort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public boolean isHttpsServing(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + hostname + "/"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;   // 유효 인증서로 핸드셰이크·응답 성공(상태코드는 무관)
        } catch (Exception e) {
            // 미연결·Caddy 미기동·인증서 미발급·자기서명·타임아웃 등 — 아직 HTTPS 안 됨.
            log.debug("HTTPS 프로브 실패(아직 미적용): hostname={} 원인={}", hostname, e.toString());
            return false;
        }
    }
}
