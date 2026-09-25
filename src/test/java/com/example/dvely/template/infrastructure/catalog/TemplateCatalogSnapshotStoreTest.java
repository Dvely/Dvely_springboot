package com.example.dvely.template.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.template.domain.model.Template;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스냅샷 저장소가 <b>어떤 실패로도 본 기능을 막지 않는다</b>는 것을 본다 (#395).
 */
class TemplateCatalogSnapshotStoreTest {

    private static final String URL = "https://example.test/catalog.json";

    private static final List<Template> TEMPLATES = List.of(new Template(
            "landing-minimal", "미니멀 랜딩", "한 장짜리 랜딩", List.of("landing"), "vanilla", "index.html",
            List.of(new Template.ContentHint("hero.title", "index.html", "히어로 대제목")),
            "https://example.test/t/landing-minimal/",
            "https://example.test/t/landing-minimal/thumbnail.jpg",
            "https://example.test/src/landing-minimal.tar.gz"));

    /**
     * {@code save}·{@code restore} 에 {@code @Transactional} 이 없어야 한다.
     *
     * <p><b>이 결정은 동작 테스트로 막을 수 없다.</b> 단위 테스트에는 프록시가 없어서
     * {@code @Transactional} 을 붙여도 커밋이 메서드 안에서 끝난 것처럼 보이고, 예외는 얌전히
     * try/catch 에 잡힌다 — 붙인 채로도 테스트가 통과한다. 실제로 이 구현은 그렇게 한 번 거짓
     * 통과했다.</p>
     *
     * <p>운영에서는 프록시가 커밋을 메서드 <b>밖에서</b> 하므로 플러시·커밋 실패가 catch 를 지나쳐
     * 호출자에게 올라간다. 호출자는 그것을 "카탈로그 갱신 실패"로 읽고, cold start 라면 네트워크가
     * 성공했는데도 503 을 낸다. 그래서 애노테이션 자체를 고정한다.</p>
     */
    @Test
    @DisplayName("save·restore 에 @Transactional 이 없다 — 커밋이 catch 밖에서 일어나면 안 된다")
    void doesNotDeclareTransactional() throws NoSuchMethodException {
        Method save = TemplateCatalogSnapshotStore.class
                .getMethod("save", List.class, Instant.class, String.class);
        Method restore = TemplateCatalogSnapshotStore.class
                .getMethod("restore", String.class, Duration.class);

        assertThat(save.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(restore.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(TemplateCatalogSnapshotStore.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("저장이 터져도 예외를 밖으로 내보내지 않는다")
    void swallowsSaveFailure() {
        TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);
        Mockito.when(repository.save(Mockito.any())).thenThrow(new RuntimeException("DB 가 죽었다"));

        TemplateCatalogSnapshotStore store = new TemplateCatalogSnapshotStore(repository);

        // 던지지 않는 것으로 끝이다. 호출자는 이미 응답할 수 있는 상태다.
        store.save(TEMPLATES, Instant.now(), URL);
    }

    @Test
    @DisplayName("복원 중 어떤 예외가 나도 비워서 돌려준다 — 스냅샷이 없는 것과 같이 동작한다")
    void swallowsRestoreFailure() {
        TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);
        Mockito.when(repository.findById(Mockito.anyByte())).thenThrow(new RuntimeException("DB 가 죽었다"));

        TemplateCatalogSnapshotStore store = new TemplateCatalogSnapshotStore(repository);

        assertThat(store.restore(URL, Duration.ofHours(24))).isEmpty();
    }

    @Test
    @DisplayName("payload 가 깨져 있으면 복원하지 않는다 — 반쯤 읽은 목록을 내보내지 않는다")
    void refusesCorruptPayload() {
        TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);
        Mockito.when(repository.findById(Mockito.anyByte())).thenReturn(Optional.of(
                TemplateCatalogSnapshotEntity.of("{이건 JSON 이 아니다", Instant.now(), URL)));

        TemplateCatalogSnapshotStore store = new TemplateCatalogSnapshotStore(repository);

        assertThat(store.restore(URL, Duration.ofHours(24))).isEmpty();
    }

    @Test
    @DisplayName("스킴 검사가 없던 때 저장된 스냅샷은 복원 단계에서 걸러진다")
    void refusesSnapshotThatPredatesUrlPolicy() {
        // 검증이 붙기 전에는 이런 값이 그대로 저장될 수 있었다. 복원은 수신과 같은 검사를 통과해야 한다.
        String poisoned = """
                [{"id":"landing-minimal","name":"n","description":"d","tags":[],"stack":"vanilla",
                  "entry":"index.html","contentHints":[],"demoUrl":"javascript:alert(1)",
                  "thumbnailUrl":null,"sourceUrl":"https://example.test/src/x.tar.gz"}]
                """;
        TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);
        Mockito.when(repository.findById(Mockito.anyByte())).thenReturn(Optional.of(
                TemplateCatalogSnapshotEntity.of(poisoned, Instant.now(), URL)));

        TemplateCatalogSnapshotStore store = new TemplateCatalogSnapshotStore(repository);

        assertThat(store.restore(URL, Duration.ofHours(24))).isEmpty();
    }

    @Test
    @DisplayName("정상 스냅샷은 나이와 함께 돌려준다 — 직렬화 왕복을 실제로 태운다")
    void restoresWithAge() {
        TemplateCatalogSnapshotRepository repository =
                Mockito.mock(TemplateCatalogSnapshotRepository.class);
        // save 가 넘긴 엔티티를 그대로 붙잡아 findById 가 돌려준다. 테스트가 직렬화 형식을
        // 다시 쓰지 않으므로, 형식이 바뀌어도 이 테스트는 계속 왕복만 본다.
        TemplateCatalogSnapshotEntity[] slot = new TemplateCatalogSnapshotEntity[1];
        Mockito.when(repository.save(Mockito.any())).thenAnswer(invocation -> {
            TemplateCatalogSnapshotEntity saved = invocation.getArgument(0);
            // 2시간 전에 받은 것으로 바꿔 둔다 — 나이가 실제로 계산되는지 보기 위해.
            slot[0] = TemplateCatalogSnapshotEntity.of(
                    saved.getPayload(), Instant.now().minus(Duration.ofHours(2)), saved.getSourceUrl());
            return saved;
        });
        Mockito.when(repository.findById(Mockito.anyByte()))
                .thenAnswer(invocation -> Optional.ofNullable(slot[0]));

        TemplateCatalogSnapshotStore store = new TemplateCatalogSnapshotStore(repository);
        store.save(TEMPLATES, Instant.now(), URL);
        Optional<TemplateCatalogSnapshotStore.Restored> restored = store.restore(URL, Duration.ofHours(24));

        assertThat(restored).isPresent();
        assertThat(restored.get().templates()).hasSize(1);
        assertThat(restored.get().templates().getFirst().contentHints()).singleElement()
                .satisfies(hint -> assertThat(hint.desc()).isEqualTo("히어로 대제목"));
        assertThat(restored.get().templates().getFirst().thumbnailUrl())
                .isEqualTo("https://example.test/t/landing-minimal/thumbnail.jpg");
        assertThat(restored.get().age()).isBetween(Duration.ofMinutes(119), Duration.ofMinutes(121));
    }
}
