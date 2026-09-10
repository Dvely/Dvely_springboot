package com.example.dvely.template.infrastructure.catalog;

import com.example.dvely.template.application.exception.TemplateCatalogUnavailableException;
import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import com.example.dvely.template.domain.model.Template;
import com.example.dvely.template.infrastructure.config.TemplateCatalogProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 템플릿 저장소가 Pages 로 발행한 catalog.json 을 읽는다.
 *
 * 갱신 실패 시 직전에 읽어둔 것을 계속 쓴다(stale-while-error). 카탈로그는 정적 문서이고 자주
 * 바뀌지 않으므로, 잠깐 낡은 목록을 보여주는 편이 프로젝트 생성 자체를 막는 것보다 낫다. 다만
 * 낡은 것을 쓰고 있다는 사실은 WARN 으로 남긴다 — 조용히 넘어가면 카탈로그가 며칠째 갱신되지
 * 않는 것을 아무도 모른다.
 *
 * 한 번도 읽지 못한 경우에만 예외를 던진다. 그때 통과시키면 존재하지 않는 templateType 이
 * 저장되고, 씨딩 시점에 가서야 깨진다.
 */
@Slf4j
@Component
public class PagesTemplateCatalogClient implements TemplateCatalogPort {

    private final TemplateCatalogProperties properties;
    private final RestClient restClient;

    /** 갱신은 한 스레드만 시도한다. 동시에 여러 요청이 들어와도 카탈로그를 여러 번 받지 않는다. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot snapshot;

    public PagesTemplateCatalogClient(TemplateCatalogProperties properties,
                                      @Qualifier("templateCatalogRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public List<Template> findAll() {
        return current().ordered();
    }

    @Override
    public Optional<Template> findById(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(current().byId().get(templateId));
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        if (cached != null && !cached.isStale(refreshInterval())) {
            return cached;
        }

        // 락을 못 잡았는데 쓸 수 있는 것이 있으면 그걸 쓴다. 갱신은 락을 잡은 스레드가 한다.
        if (!refreshLock.tryLock()) {
            if (cached != null) {
                return cached;
            }
            refreshLock.lock();
        }

        try {
            Snapshot latest = snapshot;
            if (latest != null && !latest.isStale(refreshInterval())) {
                return latest;
            }
            return refresh(latest);
        } finally {
            refreshLock.unlock();
        }
    }

    private Snapshot refresh(Snapshot previous) {
        try {
            CatalogDocument document = restClient.get()
                    .uri(properties.url())
                    .retrieve()
                    .body(CatalogDocument.class);

            if (document == null || document.templates() == null || document.templates().isEmpty()) {
                throw new IllegalStateException("카탈로그가 비어 있습니다: " + properties.url());
            }

            Snapshot fresh = Snapshot.of(document.templates());
            snapshot = fresh;
            log.info("[Template] 카탈로그 갱신: {}종 ({})", fresh.ordered().size(), properties.url());
            return fresh;
        } catch (Exception e) {
            if (previous != null) {
                log.warn("[Template] 카탈로그 갱신 실패 — 직전 목록({}종)을 계속 사용합니다: {}",
                        previous.ordered().size(), e.toString());
                // 계속 실패하면 매 요청마다 네트워크를 때린다. 갱신 시각을 당겨 다음 시도를 늦춘다.
                Snapshot deferred = previous.deferred();
                snapshot = deferred;
                return deferred;
            }
            throw new TemplateCatalogUnavailableException(
                    "템플릿 카탈로그를 불러오지 못했습니다: " + properties.url(), e);
        }
    }

    private Duration refreshInterval() {
        return properties.refreshInterval() == null ? Duration.ofMinutes(10) : properties.refreshInterval();
    }

    private record Snapshot(List<Template> ordered, Map<String, Template> byId, Instant fetchedAt) {

        static Snapshot of(List<Template> templates) {
            Map<String, Template> index = new LinkedHashMap<>();
            for (Template template : templates) {
                index.put(template.id(), template);
            }
            return new Snapshot(List.copyOf(templates), Map.copyOf(index), Instant.now());
        }

        boolean isStale(Duration interval) {
            return Instant.now().isAfter(fetchedAt.plus(interval));
        }

        /** 갱신에 실패했을 때 다음 시도를 미루기 위해 시각만 새로 찍는다. */
        Snapshot deferred() {
            return new Snapshot(ordered, byId, Instant.now());
        }
    }

    private record CatalogDocument(String generatedAt, List<Template> templates) {
    }
}
