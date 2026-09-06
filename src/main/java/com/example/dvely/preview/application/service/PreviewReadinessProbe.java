package com.example.dvely.preview.application.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 경유 경로({@code http://127.0.0.1:hostPort/})로 프리뷰 앱이 응답할 때까지 짧게 기다린다.
 *
 * <p>세션을 ACTIVE 로 올리기 <b>직전</b>에 불러, FE 가 ACTIVE 를 보고 붙이는 <b>첫 iframe 로드</b>가
 * 502/503(프록시 경로가 아직 안 뜬 창)을 맞지 않게 한다. 크롬은 프레임 안의 503 을 <b>깨진 이미지
 * 아이콘</b>으로 그리므로, 이 레이스가 사용자에게 정확히 "프리뷰가 안 보이는" 원래 증상이었다. 컨테이너
 * 준비(내부 3000 serve_ready)와 호스트→컨테이너 매핑 포트가 실제로 프록시되는 시점 사이에 짧은 공백이
 * 있어(특히 DB 네트워크 연결로 포트가 재할당된 직후) 첫 요청이 실패할 수 있다.</p>
 *
 * <p>컨테이너를 <b>직접</b> 친다({@code 127.0.0.1:hostPort}) — 게이트웨이 토큰 URL 이 아니므로 회전
 * 토큰을 소비하지 않는다. 프로비저닝 백그라운드 스레드에서 불리므로 몇 초 대기는 사용자 요청 경로를 막지
 * 않는다(사용자는 findCurrent 를 폴링한다).</p>
 */
@Slf4j
@Component
public class PreviewReadinessProbe {

    private final int maxAttempts;
    private final long intervalMs;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public PreviewReadinessProbe() {
        this(20, 500);   // ~10초 예산: 정상은 수초 안에 뜬다. 못 뜨면 best-effort 로 그냥 ACTIVE 진행.
    }

    // 테스트가 작은 예산으로 도달 불가 케이스를 빠르게 검증하기 위한 생성자.
    PreviewReadinessProbe(int maxAttempts, long intervalMs) {
        this.maxAttempts = maxAttempts;
        this.intervalMs = intervalMs;
    }

    /**
     * 도달 가능(어떤 HTTP 응답이든)해질 때까지 예산 안에서 재시도한다.
     *
     * @return 예산 안에 도달 가능해지면 true. false 여도 호출자는 ACTIVE 를 진행할 수 있다(best-effort —
     *         이 게이트는 흔한 레이스를 닫을 뿐, 활성화를 하드하게 막지 않아 기존 동작을 퇴행시키지 않는다).
     */
    public boolean awaitReachable(int hostPort) {
        String target = "http://127.0.0.1:" + hostPort + "/";
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                httpClient.send(
                        HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return true;   // 어떤 HTTP 응답이든 = 프록시 경로 준비됨
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                sleep(intervalMs);   // 아직 못 닿음 — 잠깐 뒤 재시도
            }
        }
        log.warn("[PreviewReadiness] 게이트웨이 경유 도달 확인 예산 초과(hostPort={}) — best-effort 로 진행", hostPort);
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
