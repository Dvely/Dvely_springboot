package com.example.dvely.template.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url             catalog.json 위치. 템플릿 저장소가 Pages 로 발행한다
 * @param refreshInterval 이 시간이 지나면 다음 조회 때 다시 읽는다
 * @param requestTimeout  카탈로그 한 번 읽는 데 허용하는 시간
 */
@ConfigurationProperties(prefix = "qeploy.template.catalog")
public record TemplateCatalogProperties(
        String url,
        Duration refreshInterval,
        Duration requestTimeout
) {
}
