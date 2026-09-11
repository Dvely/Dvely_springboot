package com.example.dvely.template.infrastructure.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TemplateCatalogConfig {

    /**
     * 카탈로그 전용 RestClient.
     *
     * 타임아웃을 여기서 박는다. 걸지 않으면 카탈로그가 응답하지 않을 때 프로젝트 생성 요청이
     * 함께 매달린다 — 카탈로그는 곁다리인데 본 기능을 멈춰 세우게 된다.
     *
     * 클라이언트가 빌더를 받아 직접 조립하지 않고 완성품을 받는 이유는 테스트다. 빌더를 받으면
     * MockRestServiceServer 가 심어둔 요청 팩토리를 이 설정이 덮어써서 mock 이 동작하지 않는다.
     * 테스트는 자기 빌더에 mock 을 물린 뒤 build() 한 것을 그대로 넘긴다.
     */
    @Bean
    public RestClient templateCatalogRestClient(TemplateCatalogProperties properties) {
        Duration timeout = properties.requestTimeout() == null
                ? Duration.ofSeconds(5)
                : properties.requestTimeout();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        // 공용 RestClient 빈(config/HttpClientConfig)을 쓰지 않는 이유는 상한이 다르기 때문이다 —
        // 카탈로그는 곁다리라 5 초면 포기해야 하고, 그 값은 여기 properties 로 조절한다.
        return RestClient.builder().requestFactory(factory).build();
    }
}
