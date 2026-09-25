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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private record Fixture(PagesTemplateCatalogClient client, MockRestServiceServer server) {
    }

    private Fixture fixture(Duration refreshInterval) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateCatalogProperties properties =
                new TemplateCatalogProperties(URL, refreshInterval, Duration.ofSeconds(5));
        return new Fixture(new PagesTemplateCatalogClient(properties, builder.build()), server);
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
}
