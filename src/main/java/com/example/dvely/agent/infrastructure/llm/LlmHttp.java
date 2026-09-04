package com.example.dvely.agent.infrastructure.llm;

import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * LLM HTTP 호출의 연결·읽기 상한.
 *
 * <p>RestClient 는 기본적으로 read timeout 이 없어(무한), 응답이 오지 않는 커넥션이 그대로
 * 멈춘다. CODE 에이전트의 한 라운드는 이 호출이 끝나야 진행하므로, 행(hung)이 나면 그 태스크와
 * agentExecutor 스레드 하나가 무한정 붙잡히고 사용자에게는 실패조차 뜨지 않는다. 재시도 로직도
 * 반환되지 않는 호출은 다시 걸 수 없으니 소용이 없다. 상한을 둬 그런 호출을 끊고 재시도/명확한
 * 실패로 넘긴다.</p>
 *
 * <p>읽기 상한은 넉넉히 둔다 — 느린 모델(glm 무료 tier 는 429 재시도로 라운드당 수십 초가 걸린다)의
 * 정상 응답까지 자르면 안 되기 때문이다. 3분을 넘겨 오지 않으면 사실상 행으로 본다.</p>
 */
final class LlmHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    private LlmHttp() {
    }

    static ClientHttpRequestFactory timeoutFactory() {
        return factory(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** 상한을 인자로 받는다 — 테스트가 짧은 값으로 타임아웃 동작을 빠르게 확인할 수 있게. */
    static ClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
