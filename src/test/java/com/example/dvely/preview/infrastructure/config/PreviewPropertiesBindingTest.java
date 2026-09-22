package com.example.dvely.preview.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code application.yaml} 의 {@code qeploy.preview.*} 키가 실제로 읽히는지 본다.
 *
 * <p>이 검사가 필요한 이유는 <b>틀렸을 때 아무 일도 안 일어나기 때문</b>이다. 키 이름이 어긋나면
 * Spring 은 조용히 무시하고 값은 기본값으로 남는다. 그 기본값이 yaml 에 적힌 값과 같으면
 * (지금 {@code approval-hold} 가 양쪽 다 6시간이다) 오동작조차 없어서, 설정을 바꿔도 안 먹는다는
 * 것을 누군가 운영에서 겪기 전까지 아무도 모른다.</p>
 *
 * <p>#376 에서 {@code binding-approval-hold} 를 {@code approval-hold} 로 바꾸면서 실제로 이 위험을
 * 안고 배포했다 — 기동은 정상이었고 확인할 방법이 없었다. 그래서 검사를 남긴다.</p>
 *
 * <p>읽는 쪽은 두 갈래다: {@link PreviewProperties} 의 setter, 또는 {@code ${qeploy.preview.…}}
 * 플레이스홀더({@code @Value} · {@code fixedDelayString}). 어느 쪽도 아니면 그 키는 죽은 설정이다.</p>
 */
class PreviewPropertiesBindingTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    @DisplayName("qeploy.preview 의 모든 스칼라 키를 누군가 읽는다 — 죽은 설정이 없다")
    void everyPreviewKeyIsActuallyRead() throws IOException {
        Set<String> setters = relaxedSetterNames();
        String sources = readAllSources();

        List<String> orphans = new ArrayList<>();
        previewBlock().forEach((key, value) -> {
            if (value instanceof Map) {
                return;   // 중첩 블록(egress 등)은 자기 @ConfigurationProperties 가 따로 받는다
            }
            boolean boundToProperties = setters.contains(relaxed(key));
            boolean readAsPlaceholder = sources.contains("${qeploy.preview." + key);
            if (!boundToProperties && !readAsPlaceholder) {
                orphans.add(key);
            }
        });

        assertThat(orphans)
                .as("qeploy.preview 아래 이 키들을 읽는 곳이 없다 — PreviewProperties 의 setter 도, "
                        + "${qeploy.preview.…} 플레이스홀더도 없다. 설정을 바꿔도 아무 일이 일어나지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("approval-hold 가 PreviewProperties 에 바인딩된다 — 승인 대기 중 프리뷰 회수를 막는 값(#376)")
    void approvalHoldBindsToTheProperty() throws IOException {
        assertThat(previewBlock())
                .as("설정 키가 사라지면 승인 대기 중 프리뷰가 30분에 회수된다")
                .containsKey("approval-hold");
        assertThat(relaxedSetterNames())
                .as("yaml 은 approval-hold 인데 PreviewProperties 에 맞는 setter 가 없다 — 조용히 무시된다")
                .contains(relaxed("approval-hold"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> previewBlock() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(in).as("application.yaml 을 클래스패스에서 찾지 못했다").isNotNull();
            Map<String, Object> qeploy = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(in)).get("qeploy");
            assertThat(qeploy).as("qeploy 블록이 없다").isNotNull();
            Map<String, Object> preview = (Map<String, Object>) qeploy.get("preview");
            assertThat(preview).as("qeploy.preview 블록이 없다").isNotNull();
            return preview;
        }
    }

    private String readAllSources() throws IOException {
        assertThat(Files.isDirectory(MAIN_JAVA))
                .as("src/main/java 를 찾지 못했다 — 이 검사는 프로젝트 루트에서 돌아야 한다")
                .isTrue();
        StringBuilder all = new StringBuilder();
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(p));
            }
        }
        return all.toString();
    }

    private Set<String> relaxedSetterNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Method m : PreviewProperties.class.getMethods()) {
            if (m.getName().startsWith("set") && m.getParameterCount() == 1) {
                names.add(relaxed(m.getName().substring(3)));
            }
        }
        return names;
    }

    /** {@code approval-hold}, {@code approvalHold}, {@code ApprovalHold} 를 같은 것으로 본다. */
    private String relaxed(String name) {
        return name.replace("-", "").replace("_", "").toLowerCase();
    }
}
