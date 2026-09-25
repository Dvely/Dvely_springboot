package com.example.dvely.template.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url             catalog.json 위치. 템플릿 저장소가 Pages 로 발행한다
 * @param refreshInterval 이 시간이 지나면 다음 조회 때 다시 읽는다
 * @param requestTimeout  카탈로그 한 번 읽는 데 허용하는 시간
 * @param snapshotMaxAge  DB 스냅샷을 복원해도 되는 최대 나이 (#395). 재시작 직후 네트워크가
 *                        죽어 있을 때만 쓰인다. 이보다 낡으면 복원하지 않고 503 을 낸다 —
 *                        너무 낡은 목록은 이미 없어진 템플릿을 고르게 만들고, 그러면
 *                        {@code TemplateCatalogGuard} 가 막으려던 "고를 때는 200, 만들 때는
 *                        실패"가 된다
 */
@ConfigurationProperties(prefix = "qeploy.template.catalog")
public record TemplateCatalogProperties(
        String url,
        Duration refreshInterval,
        Duration requestTimeout,
        Duration snapshotMaxAge
) {
}
