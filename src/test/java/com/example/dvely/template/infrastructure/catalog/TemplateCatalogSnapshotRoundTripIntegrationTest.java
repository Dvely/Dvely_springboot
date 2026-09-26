package com.example.dvely.template.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.template.domain.model.Template;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 스냅샷 왕복을 <b>실제 MySQL</b> 위에서 본다 (#395).
 *
 * <h2>왜 단위 테스트로는 부족한가</h2>
 * {@code TemplateCatalogSnapshotStoreTest} 는 리포지토리를 목으로 두므로 JDBC 를 타지 않는다.
 * 그런데 {@code fetched_at} 은 {@code Instant} 이고 컬럼은 {@code DATETIME(6)} 이며 JDBC URL 은
 * {@code serverTimezone=Asia/Seoul} 이다 — <b>쓸 때와 읽을 때의 시간대 변환이 어긋나면 나이가
 * 9시간씩 밀린다.</b> dev 에서 행을 직접 보니 MySQL {@code NOW()} 와 9시간 차이가 났다(저장은 UTC,
 * {@code NOW()} 는 KST). 그것 자체는 정상이지만, 그 변환이 <b>대칭</b>인지는 여기서만 확인된다.
 *
 * <p>어긋나면 조용히 망가진다. 나이가 부풀려지면 상한(기본 24h)에 먼저 걸려 복원이 안 되고,
 * 줄어들면 너무 낡은 목록을 복원한다. 어느 쪽도 예외를 던지지 않으므로 아무도 모른다.</p>
 */
@SpringBootTest
class TemplateCatalogSnapshotRoundTripIntegrationTest {

    private static final String URL = "https://round-trip.test/catalog.json";

    private static final List<Template> TEMPLATES = List.of(new Template(
            "landing-minimal", "미니멀 랜딩", "한 장짜리 랜딩", List.of("landing"), "vanilla", "index.html",
            List.of(new Template.ContentHint("hero.title", "index.html", "히어로 대제목")),
            "https://round-trip.test/t/landing-minimal/",
            "https://round-trip.test/t/landing-minimal/thumbnail.jpg",
            "https://round-trip.test/src/landing-minimal.tar.gz"));

    @Autowired
    private TemplateCatalogSnapshotStore store;

    @Autowired
    private TemplateCatalogSnapshotRepository repository;

    @Test
    @DisplayName("DB 를 거쳐 돌아온 나이가 실제 경과 시간과 같다 — 시간대 변환이 대칭이다")
    void ageSurvivesTheDatabaseRoundTrip() {
        Instant fetchedAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MICROS);

        store.save(TEMPLATES, fetchedAt, URL);
        Optional<TemplateCatalogSnapshotStore.Restored> restored = store.restore(URL, Duration.ofHours(24));

        assertThat(restored).isPresent();
        // 3시간으로 넣었으니 3시간으로 돌아와야 한다. 시간대가 어긋나면 여기가 12시간이나
        // -6시간이 된다 — 그리고 그때도 예외는 나지 않는다.
        assertThat(restored.get().age())
                .isBetween(Duration.ofMinutes(179), Duration.ofMinutes(181));
    }

    @Test
    @DisplayName("저장한 그 시각이 그대로 읽힌다")
    void instantIsStoredAndReadBackUnchanged() {
        Instant fetchedAt = Instant.now().minus(Duration.ofMinutes(37)).truncatedTo(ChronoUnit.MICROS);

        store.save(TEMPLATES, fetchedAt, URL);

        TemplateCatalogSnapshotEntity row =
                repository.findById(TemplateCatalogSnapshotEntity.SINGLETON_ID).orElseThrow();
        assertThat(row.getFetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("상한 판정이 실제 DB 값으로도 맞다 — 상한을 넘긴 것은 복원하지 않는다")
    void refusesTooOldAcrossTheRoundTrip() {
        store.save(TEMPLATES, Instant.now().minus(Duration.ofHours(25)), URL);

        assertThat(store.restore(URL, Duration.ofHours(24))).isEmpty();
        // 상한을 늘리면 같은 행이 복원된다 — 거부 이유가 나이였음을 확인한다.
        assertThat(store.restore(URL, Duration.ofHours(48))).isPresent();
    }

    @Test
    @DisplayName("두 번 저장하면 행이 하나로 덮인다 — 트랜잭션 없이도 UPDATE 로 간다")
    void secondSaveReplacesTheRowInsteadOfInserting() {
        store.save(TEMPLATES, Instant.now().minus(Duration.ofHours(5)), URL);
        store.save(TEMPLATES, Instant.now().minus(Duration.ofMinutes(1)), URL);

        assertThat(repository.count()).isEqualTo(1);
        // 나중 것이 남아야 한다. detached 엔티티를 더티체킹으로 고치려 했다면 여기서 5시간이 나온다.
        assertThat(store.restore(URL, Duration.ofHours(24)).orElseThrow().age())
                .isLessThan(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("contentHints 까지 왕복한다 — 반쯤 복원된 목록으로 에이전트를 돌리지 않는다")
    void contentHintsSurviveTheRoundTrip() {
        store.save(TEMPLATES, Instant.now(), URL);

        List<Template> templates = store.restore(URL, Duration.ofHours(24)).orElseThrow().templates();

        assertThat(templates).singleElement().satisfies(template -> {
            assertThat(template.contentHints()).singleElement().satisfies(hint -> {
                assertThat(hint.key()).isEqualTo("hero.title");
                assertThat(hint.where()).isEqualTo("index.html");
                assertThat(hint.desc()).isEqualTo("히어로 대제목");
            });
            assertThat(template.tags()).containsExactly("landing");
            assertThat(template.sourceUrl()).isEqualTo("https://round-trip.test/src/landing-minimal.tar.gz");
        });
    }
}
