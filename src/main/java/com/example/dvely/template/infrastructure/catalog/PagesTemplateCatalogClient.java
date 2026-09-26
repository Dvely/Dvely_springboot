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
 *
 * <p>다만 그 "한 번도"는 <b>이 프로세스가 뜬 뒤로</b>였다 — 캐시가 메모리에만 있어서 재시작마다
 * cold start 였고, 배포가 곧 그 순간이다. Dvely_FE#117 이후 FE 다섯 화면이 전부 이 API 하나만
 * 보므로, 그 겹침이 FE 전체를 503 으로 만든다. 그래서 성공한 갱신을 DB 에 남기고, cold start 에서
 * 네트워크가 실패했을 때만 되살린다(#395).</p>
 *
 * <p>받은 문서의 URL 스킴도 여기서 본다. 검사 없이 통과시키던 값이 FE 의 iframe·img 로 그대로
 * 들어가고 있었다 — 이 서버가 유일한 병목이라 여기서 막으면 모든 소비자가 덮인다.</p>
 */
@Slf4j
@Component
public class PagesTemplateCatalogClient implements TemplateCatalogPort {

    /** 스냅샷 복원 상한의 기본값. 설정이 비어 있을 때만 쓴다. */
    private static final Duration DEFAULT_SNAPSHOT_MAX_AGE = Duration.ofHours(24);

    private final TemplateCatalogProperties properties;
    private final RestClient restClient;
    private final TemplateCatalogSnapshotStore snapshotStore;

    /** 갱신은 한 스레드만 시도한다. 동시에 여러 요청이 들어와도 카탈로그를 여러 번 받지 않는다. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot snapshot;

    public PagesTemplateCatalogClient(TemplateCatalogProperties properties,
                                      @Qualifier("templateCatalogRestClient") RestClient restClient,
                                      TemplateCatalogSnapshotStore snapshotStore) {
        this.properties = properties;
        this.restClient = restClient;
        this.snapshotStore = snapshotStore;
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
            // 스냅샷을 만들기 전에 본다. 통과시킨 뒤 저장하면 나쁜 값이 DB 에 남아 재시작마다
            // 되살아난다.
            TemplateCatalogUrlPolicy.assertBrowserSafe(document.templates());

            Snapshot fresh = Snapshot.of(document.templates());
            snapshot = fresh;
            log.info("[Template] 카탈로그 갱신: {}종 ({})", fresh.ordered().size(), properties.url());
            // 서빙을 시작한 뒤에 저장한다. 저장이 느리거나 실패해도 이 요청은 이미 답할 수 있다.
            snapshotStore.save(fresh.ordered(), Instant.now(), properties.url());
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
            // 여기가 cold start 다. 이 프로세스는 아직 한 번도 성공하지 못했지만, 재시작 전의
            // 우리(또는 다른 인스턴스)가 성공했을 수 있다.
            Snapshot restored = restoreFromSnapshot();
            if (restored != null) {
                return restored;
            }
            throw new TemplateCatalogUnavailableException(
                    "템플릿 카탈로그를 불러오지 못했습니다: " + properties.url(), e);
        }
    }

    /**
     * DB 스냅샷으로 시작한다. 되살릴 것이 없으면 {@code null} — 호출자는 그때 503 을 낸다.
     *
     * <p>복원한 스냅샷의 시각을 <b>받은 시각이 아니라 지금</b>으로 찍는다. 원래 시각으로 두면
     * 곧바로 stale 이 되어 다음 요청마다 죽은 네트워크를 다시 때린다. 나이 판단은 이미 store 가
     * 끝냈으므로 여기서는 스케줄링만 생각한다.</p>
     */
    private Snapshot restoreFromSnapshot() {
        return snapshotStore.restore(properties.url(), snapshotMaxAge())
                .map(restored -> {
                    Snapshot fromDb = Snapshot.of(restored.templates());
                    snapshot = fromDb;
                    return fromDb;
                })
                .orElse(null);
    }

    private Duration refreshInterval() {
        return properties.refreshInterval() == null ? Duration.ofMinutes(10) : properties.refreshInterval();
    }

    private Duration snapshotMaxAge() {
        return properties.snapshotMaxAge() == null ? DEFAULT_SNAPSHOT_MAX_AGE : properties.snapshotMaxAge();
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
