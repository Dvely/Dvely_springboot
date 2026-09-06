package com.example.dvely.provisioning.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 앱의 헬스 엔드포인트가 <b>기능적 이상</b>을 명시적으로 보고하는지 본다(HTTP 5xx). {@link TcpHealthChecker}
 * 는 포트가 열렸는지(프로세스 살아 있는지)만 보므로, 앱이 뜬 채로 DB 등에 못 붙어도 healthy 로 잡혔다 —
 * 배포 e2e 에서 실제로 그랬다(앱 루트 200 인데 {@code /api/health} 는 500 "database: down", 그래도 healthy=1).
 * 이 프로브가 그 공백을 메운다.
 *
 * <p><b>보수적으로 5xx 만</b> 이상으로 본다. 헬스 엔드포인트가 없거나(404), 2xx 로 정상이거나, 아예 HTTP
 * 응답이 없으면(타임아웃·비-HTTP) 이상으로 <b>보지 않는다</b> — 그 경우는 TCP 판정을 그대로 따른다. 즉 이
 * 프로브는 "명시적 5xx" 라는 확실한 신호에서만 healthy 를 끌어내려, 헬스 엔드포인트를 안 가진 앱의 기존
 * 동작(TCP)을 절대 바꾸지 않는다.</p>
 */
@Slf4j
@Component
public class HttpHealthProbe {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // 앱의 헬스 경로. 대다수 생성 백엔드가 /api/health 를 노출한다(실측). 다른 경로를 쓰는 배포를 위해 설정.
    @Value("${qeploy.provisioning.health-path:/api/health}")
    private String healthPath;

    /** 헬스 엔드포인트가 5xx(기능적 이상)를 돌려주면 true. 그 외(404·2xx·무응답)는 false = TCP 판정에 맡김. */
    public boolean reportsUnhealthy(String host, int port) {
        String url = "http://" + host + ":" + port + healthPath;
        try {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 500) {
                log.debug("HTTP 헬스 이상(5xx): url={} status={}", url, response.statusCode());
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;   // 인터럽트는 앱 상태의 증거가 아니다
        } catch (Exception e) {
            // 무응답·타임아웃·헬스 경로 없음(비-HTTP 등) — 기능적 이상으로 단정하지 않는다(TCP 판정 유지).
            return false;
        }
    }
}
