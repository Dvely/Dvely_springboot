package com.example.dvely.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 HTTP 호출의 공용 전송 계층.
 *
 * <p>여기 있는 빈들이 존재하는 첫 번째 이유는 <b>상한</b>이다. {@code RestClient.create()} 는 read
 * timeout 이 없어(무한) 상대가 응답을 끊지 않으면 요청 스레드가 영원히 묶인다. 2026-09-08 dev 에서
 * DB 커넥션 풀이 22 분간 고갈됐던 사고와 같은 모양이다 — 트랜잭션 경계를 나중에 손보더라도, 상한이
 * 없으면 스레드가 무한히 붙잡히는 것 자체는 그대로 남는다.</p>
 *
 * <p>두 번째 이유는 재사용이다. 호출마다 클라이언트를 만들면 커넥션 풀과 셀렉터 스레드가 매번 새로
 * 생기고 닫히지 않는다 — 커넥션을 한 번도 재사용하지 못한 채 TLS 핸드셰이크를 매번 다시 한다.</p>
 */
@Configuration
public class HttpClientConfig {

    /**
     * 요청 팩토리를 명시하는 이유.
     *
     * <p>지정하지 않으면 Spring 이 클래스패스를 훑어 고르는데, 지금 1 순위로 뽑히는 Apache
     * HttpClient5 는 이 프로젝트가 선언한 적 없는 AWS SDK 의 전이 의존이다. AWS 의존이 바뀌면
     * 전송 계층이 소리 없이 바뀐다. 게다가 Apache 기본 풀은 라우트당 5 커넥션이라, 한 호스트
     * (api.github.com) 로 동시 호출이 5 를 넘으면 나머지가 풀에서 대기한다 — 상한을 걸어 풀린
     * 스레드를 다른 곳에서 다시 묶는 셈이다. JDK 클라이언트는 라우트당 상한이 없고 HTTP/2 로
     * 한 커넥션에 여러 요청을 다중화한다.</p>
     *
     * <p>{@code followRedirects} 는 NORMAL 로 둔다. GitHub Actions 의 job 로그 엔드포인트가 302 로
     * 스토리지 URL 을 돌려주므로, 따라가지 않으면 로그 본문 대신 빈 응답을 읽게 된다.</p>
     */
    @Bean
    public HttpClient sharedHttpClient(
            @Value("${qeploy.http.connect-timeout-seconds:5}") int connectTimeoutSeconds
    ) {
        return HttpClient.newBuilder()
                // 연결이 이만큼 걸리는 상대는 느린 게 아니라 닿지 않는 것이다. 재시도가 아니라
                // 실패로 넘겨야 요청 스레드가 풀린다.
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GitHub REST(JSON) 용.
     *
     * <p>30 초 근거: GitHub REST 는 정상일 때 1 초 안에 답한다. 30 초는 "느리다" 가 아니라 "죽었다"
     * 의 신호이고, 그 판단을 사용자 요청 스레드를 붙든 채로 기다릴 이유가 없다.</p>
     */
    @Bean
    public RestClient githubRestClient(
            HttpClient sharedHttpClient,
            @Value("${qeploy.http.github.read-timeout-seconds:30}") int readTimeoutSeconds
    ) {
        return RestClient.builder()
                .requestFactory(requestFactory(sharedHttpClient, readTimeoutSeconds))
                .build();
    }

    /**
     * GitHub Actions job 로그 다운로드 전용.
     *
     * <p>따로 두는 이유는 JDK 팩토리의 read timeout 이 소켓 유휴 시간이 아니라 <b>교환 전체</b>의
     * 상한이기 때문이다 — 시간이 지나면 Spring 이 응답 스트림을 닫는다. 본문이 작은 JSON 인 다른
     * 호출과 달리 이 응답은 로그 전문(수백 KB~수 MB)이라, 같은 30 초를 씌우면 정상 다운로드가
     * 중간에 잘린다. 60 초는 그 10 배 여유를 주면서도 Tomcat 스레드 점유를 1 분으로 묶는다.</p>
     */
    @Bean
    public RestClient githubLogRestClient(
            HttpClient sharedHttpClient,
            @Value("${qeploy.http.github.log-read-timeout-seconds:60}") int readTimeoutSeconds
    ) {
        return RestClient.builder()
                .requestFactory(requestFactory(sharedHttpClient, readTimeoutSeconds))
                .build();
    }

    /**
     * Cloudflare DNS API 용.
     *
     * <p>GitHub 보다 짧게 잡는다. 오가는 것은 레코드 하나짜리 작은 JSON 이고, 이 호출은 사용자가
     * 도메인 연결 화면에서 기다리는 동안 일어난다 — Cloudflare 가 멈췄을 때 30 초를 더 기다리는
     * 것보다 빨리 실패로 넘겨 재시도 안내를 띄우는 편이 낫다.</p>
     */
    @Bean
    public RestClient cloudflareRestClient(
            HttpClient sharedHttpClient,
            @Value("${qeploy.http.cloudflare.read-timeout-seconds:15}") int readTimeoutSeconds
    ) {
        return RestClient.builder()
                .requestFactory(requestFactory(sharedHttpClient, readTimeoutSeconds))
                .build();
    }

    /** 커넥션 풀·셀렉터 스레드는 {@code sharedHttpClient} 하나를 공유하고, 상한만 프로파일별로 다르다. */
    private static ClientHttpRequestFactory requestFactory(HttpClient httpClient, int readTimeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }
}
