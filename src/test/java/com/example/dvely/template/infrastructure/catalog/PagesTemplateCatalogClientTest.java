package com.example.dvely.template.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dvely.template.application.exception.TemplateCatalogUnavailableException;
import com.example.dvely.template.domain.model.Template;
import com.example.dvely.template.infrastructure.config.TemplateCatalogProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PagesTemplateCatalogClientTest {

    private static final String URL = "https://example.test/catalog.json";

    private static final String CATALOG = """
            {
              "generatedAt": "2026-09-10T00:00:00.000Z",
              "templates": [
                {
                  "id": "landing-minimal",
                  "name": "미니멀 랜딩",
                  "description": "한 장짜리 랜딩",
                  "tags": ["landing"],
                  "stack": "vanilla",
                  "entry": "index.html",
                  "contentHints": [
                    { "key": "hero.title", "where": "index.html", "desc": "히어로 대제목" }
                  ],
                  "demoUrl": "https://example.test/t/landing-minimal/",
                  "sourceUrl": "https://example.test/src/landing-minimal.tar.gz"
                }
              ]
            }
            """;

    private record Fixture(PagesTemplateCatalogClient client, MockRestServiceServer server,
                           SnapshotSlot slot) {
    }

    private Fixture fixture(Duration refreshInterval) {
        return fixture(refreshInterval, new SnapshotSlot(), Duration.ofHours(24));
    }

    /**
     * 스냅샷 저장소는 <b>진짜</b>를 쓰고 리포지토리만 메모리로 바꾼다 (#395).
     *
     * <p>목으로 갈아치우면 Jackson 직렬화/역직렬화가 테스트를 타지 않는다. 그런데 복원 경로는
     * 평소에 돌지 않고 <b>정작 필요한 순간에만</b> 도는 코드다 — 거기가 조용히 깨져 있으면
     * 재시작 내구성이 없는 것이고, 아무도 모른다. 그래서 왕복을 실제로 돌린다.</p>
     */
    private Fixture fixture(Duration refreshInterval, SnapshotSlot slot, Duration snapshotMaxAge) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateCatalogProperties properties =
                new TemplateCatalogProperties(URL, refreshInterval, Duration.ofSeconds(5), snapshotMaxAge);
        return new Fixture(
                new PagesTemplateCatalogClient(properties, builder.build(), slot.store()),
                server,
                slot);
    }

    /** 단일 행 리포지토리를 메모리 슬롯 하나로 흉내낸다. 진짜 store 를 그 위에 얹는다. */
    private static final class SnapshotSlot {

        private TemplateCatalogSnapshotEntity held;
        private boolean saveFails;

        private final TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);

        SnapshotSlot() {
            Mockito.when(repository.findById(Mockito.anyByte()))
                    .thenAnswer(invocation -> Optional.ofNullable(held));
            Mockito.when(repository.save(Mockito.any(TemplateCatalogSnapshotEntity.class)))
                    .thenAnswer(invocation -> {
                        if (saveFails) {
                            throw new RuntimeException("DB 가 죽었다고 치자");
                        }
                        held = invocation.getArgument(0);
                        return held;
                    });
        }

        TemplateCatalogSnapshotStore store() {
            return new TemplateCatalogSnapshotStore(repository);
        }

        boolean isEmpty() {
            return held == null;
        }

        void failSaves() {
            saveFails = true;
        }

        /** 저장된 스냅샷을 그만큼 과거에 받은 것으로 되돌린다. 나이 상한을 시험하기 위해. */
        void ageBy(Duration age) {
            held = TemplateCatalogSnapshotEntity.of(
                    held.getPayload(), held.getFetchedAt().minus(age), held.getSourceUrl());
        }

        void placeFrom(String otherSourceUrl) {
            held = TemplateCatalogSnapshotEntity.of(held.getPayload(), held.getFetchedAt(), otherSourceUrl);
        }
    }

    @Test
    @DisplayName("카탈로그를 읽어 목록과 단건 조회를 제공한다")
    void readsCatalog() {
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        List<Template> templates = f.client().findAll();

        assertThat(templates).hasSize(1);
        Template template = templates.getFirst();
        assertThat(template.id()).isEqualTo("landing-minimal");
        assertThat(template.demoUrl()).isEqualTo("https://example.test/t/landing-minimal/");
        assertThat(template.contentHints()).singleElement()
                .satisfies(hint -> assertThat(hint.key()).isEqualTo("hero.title"));
        assertThat(f.client().findById("landing-minimal")).isPresent();
        assertThat(f.client().findById("does-not-exist")).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("썸네일을 아직 내보내지 않은 카탈로그도 그대로 읽는다 — thumbnailUrl 은 null 이 된다")
    void toleratesCatalogWithoutThumbnailUrl() {
        // 카탈로그는 Pages 에서 실시간으로 받아온다. 썸네일을 내보내는 템플릿 저장소 변경이
        // 발행되기 전에는 이 필드가 없는데, 그때 템플릿 API 전체가 깨지면 안 된다.
        // 위 CATALOG 상수가 정확히 그 상태(thumbnailUrl 없음)다.
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        List<Template> templates = f.client().findAll();

        assertThat(templates).hasSize(1);
        assertThat(templates.getFirst().thumbnailUrl()).isNull();
        // 나머지 필드는 그대로여야 한다 — 없는 필드 하나가 문서 전체의 파싱을 망치지 않는다.
        assertThat(templates.getFirst().demoUrl()).isEqualTo("https://example.test/t/landing-minimal/");
        f.server().verify();
    }

    @Test
    @DisplayName("썸네일이 있는 카탈로그는 그 주소를 그대로 통과시킨다")
    void passesThumbnailUrlThrough() {
        String withThumbnail = CATALOG.replace(
                "\"demoUrl\": \"https://example.test/t/landing-minimal/\"",
                "\"demoUrl\": \"https://example.test/t/landing-minimal/\",\n"
                        + "          \"thumbnailUrl\": \"https://example.test/t/landing-minimal/thumbnail.jpg\"");
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(withThumbnail, MediaType.APPLICATION_JSON));

        List<Template> templates = f.client().findAll();

        assertThat(templates.getFirst().thumbnailUrl())
                .isEqualTo("https://example.test/t/landing-minimal/thumbnail.jpg");
        f.server().verify();
    }

    @Test
    @DisplayName("갱신 주기 안에서는 다시 읽지 않는다")
    void cachesWithinRefreshInterval() {
        Fixture f = fixture(Duration.ofMinutes(10));
        // 한 번만 응답하도록 걸어둔다. 두 번 읽으면 verify 가 아니라 여기서 먼저 실패한다.
        f.server().expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        f.client().findAll();
        f.client().findAll();
        f.client().findById("landing-minimal");

        f.server().verify();
    }

    @Test
    @DisplayName("갱신에 실패하면 직전 목록을 계속 쓴다 — 프로젝트 생성을 막지 않는다")
    void servesStaleWhenRefreshFails() {
        // 주기를 0 으로 둬서 두 번째 조회가 곧바로 갱신을 시도하게 만든다.
        Fixture f = fixture(Duration.ZERO);
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(URL)).andRespond(withServerError());

        assertThat(f.client().findAll()).hasSize(1);
        assertThat(f.client().findAll()).hasSize(1);

        f.server().verify();
    }

    @Test
    @DisplayName("한 번도 읽지 못했으면 조용히 비우지 않고 예외를 던진다")
    void failsWhenNeverLoaded() {
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(f.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class)
                .hasMessageContaining(URL);
    }

    @Test
    @DisplayName("빈 카탈로그는 성공으로 보지 않는다")
    void rejectsEmptyCatalog() {
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL))
                .andRespond(withSuccess("{\"templates\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(f.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class);
    }

    // ------------------------------------------------------------------
    // URL 스킴 검증 (#395)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("demoUrl 이 javascript: 면 문서 전체를 거부한다 — FE 가 iframe src 에 넣는 값이다")
    void rejectsJavascriptDemoUrl() {
        String poisoned = CATALOG.replace(
                "\"https://example.test/t/landing-minimal/\"",
                "\"javascript:alert(1)\"");
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(poisoned, MediaType.APPLICATION_JSON));

        assertThatThrownBy(f.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class);
        // 나쁜 값이 DB 에 남으면 재시작마다 되살아난다. 저장까지 가지 않아야 한다.
        assertThat(f.slot().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("thumbnailUrl 이 data: 면 문서 전체를 거부한다")
    void rejectsDataThumbnailUrl() {
        String poisoned = CATALOG.replace(
                "\"demoUrl\": \"https://example.test/t/landing-minimal/\"",
                "\"demoUrl\": \"https://example.test/t/landing-minimal/\",\n"
                        + "          \"thumbnailUrl\": \"data:text/html;base64,PHNjcmlwdD4=\"");
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(poisoned, MediaType.APPLICATION_JSON));

        assertThatThrownBy(f.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("스킴이 나쁜 문서가 와도 직전 목록은 유지된다 — 조용히 비우지 않는다")
    void keepsPreviousWhenNewDocumentIsPoisoned() {
        Fixture f = fixture(Duration.ZERO);
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(
                CATALOG.replace("\"https://example.test/t/landing-minimal/\"", "\"javascript:alert(1)\""),
                MediaType.APPLICATION_JSON));

        assertThat(f.client().findAll()).hasSize(1);
        List<Template> afterPoison = f.client().findAll();

        assertThat(afterPoison).hasSize(1);
        assertThat(afterPoison.getFirst().demoUrl()).isEqualTo("https://example.test/t/landing-minimal/");
        f.server().verify();
    }

    @Test
    @DisplayName("http 는 막지 않는다 — 코드 실행 벡터가 아니고 로컬 템플릿 서버가 쓴다")
    void allowsPlainHttp() {
        String plainHttp = CATALOG.replace("https://example.test/t/", "http://localhost:8080/t/");
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(plainHttp, MediaType.APPLICATION_JSON));

        assertThat(f.client().findAll().getFirst().demoUrl())
                .isEqualTo("http://localhost:8080/t/landing-minimal/");
    }

    // ------------------------------------------------------------------
    // 재시작 내구성 (#395)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("성공한 갱신은 DB 에 남는다")
    void persistsSuccessfulRefresh() {
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        f.client().findAll();

        assertThat(f.slot().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("스냅샷 저장이 실패해도 서빙은 계속된다 — 보조 장치가 본 기능을 막지 않는다")
    void serviceSurvivesSnapshotSaveFailure() {
        SnapshotSlot slot = new SnapshotSlot();
        slot.failSaves();
        Fixture f = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        f.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        assertThat(f.client().findAll()).hasSize(1);
    }

    @Test
    @DisplayName("재시작 후 네트워크가 죽어 있어도 DB 스냅샷이 있으면 목록이 나간다")
    void restoresFromSnapshotOnColdStart() {
        // 1단계: 한 클라이언트가 성공해서 스냅샷을 남긴다.
        SnapshotSlot slot = new SnapshotSlot();
        Fixture first = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        first.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        first.client().findAll();

        // 2단계: 재시작한 셈으로 새 클라이언트를 만든다. 메모리 캐시는 비어 있고 네트워크는 죽었다.
        Fixture restarted = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        restarted.server().expect(requestTo(URL)).andRespond(withServerError());

        List<Template> templates = restarted.client().findAll();

        assertThat(templates).hasSize(1);
        // contentHints 까지 왕복해야 한다. 여기가 깨지면 복원은 "동작하는 것처럼" 보이면서
        // 에이전트가 무엇이 내용인지 모르는 상태가 된다.
        assertThat(templates.getFirst().contentHints()).singleElement()
                .satisfies(hint -> {
                    assertThat(hint.key()).isEqualTo("hero.title");
                    assertThat(hint.where()).isEqualTo("index.html");
                    assertThat(hint.desc()).isEqualTo("히어로 대제목");
                });
        assertThat(templates.getFirst().sourceUrl())
                .isEqualTo("https://example.test/src/landing-minimal.tar.gz");
        assertThat(restarted.client().findById("landing-minimal")).isPresent();
    }

    @Test
    @DisplayName("복원한 뒤에는 죽은 네트워크를 매 요청마다 다시 때리지 않는다")
    void doesNotHammerNetworkAfterRestore() {
        SnapshotSlot slot = new SnapshotSlot();
        Fixture first = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        first.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        first.client().findAll();

        Fixture restarted = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        // 한 번만 응답하도록 걸어둔다. 복원한 스냅샷이 곧바로 stale 이면 두 번째 조회가
        // 네트워크를 다시 때리고 여기서 실패한다.
        restarted.server().expect(ExpectedCount.once(), requestTo(URL)).andRespond(withServerError());

        restarted.client().findAll();
        restarted.client().findAll();
        restarted.client().findById("landing-minimal");

        restarted.server().verify();
    }

    @Test
    @DisplayName("상한보다 낡은 스냅샷은 복원하지 않고 503 을 낸다 — 없어진 템플릿을 고르게 하지 않는다")
    void refusesSnapshotOlderThanMaxAge() {
        SnapshotSlot slot = new SnapshotSlot();
        Fixture first = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        first.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        first.client().findAll();
        slot.ageBy(Duration.ofHours(25));

        Fixture restarted = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        restarted.server().expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(restarted.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("다른 카탈로그에서 받은 스냅샷은 복원하지 않는다")
    void refusesSnapshotFromAnotherCatalog() {
        SnapshotSlot slot = new SnapshotSlot();
        Fixture first = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        first.server().expect(requestTo(URL)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        first.client().findAll();
        slot.placeFrom("https://someone-else.test/catalog.json");

        Fixture restarted = fixture(Duration.ofMinutes(10), slot, Duration.ofHours(24));
        restarted.server().expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(restarted.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("스냅샷이 아예 없으면 예전과 똑같이 503 이다 — 이 기능이 동작을 더 나쁘게 만들지 않는다")
    void behavesLikeBeforeWhenNoSnapshot() {
        Fixture f = fixture(Duration.ofMinutes(10));
        f.server().expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(f.client()::findAll)
                .isInstanceOf(TemplateCatalogUnavailableException.class)
                .hasMessageContaining(URL);
    }
}
