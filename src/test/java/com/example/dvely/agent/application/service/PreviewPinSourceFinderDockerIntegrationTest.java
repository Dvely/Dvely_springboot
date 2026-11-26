package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.ContainerFileWriter;
import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.ContainerRole;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 실제 프리뷰 컨테이너(node:20-alpine)에서 소스 후보를 찾아 본다(§16.7). BusyBox 의 grep·sed·timeout 으로 도는지,
 * 꾸민 글이 셸에서 실행되지 않는지는 데몬만 말해 준다.
 *
 * <pre>./gradlew test --tests "*PreviewPinSourceFinderDockerIntegrationTest" -Ddocker.it=true</pre>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class PreviewPinSourceFinderDockerIntegrationTest {

    private final DockerContainerService docker = new DockerContainerService();
    private final ContainerFileWriter writer = new ContainerFileWriter(docker);
    private final PreviewPinSourceFinder finder = new PreviewPinSourceFinder(docker);
    private final ObjectMapper json = new ObjectMapper();
    private String containerId;

    @BeforeEach
    void start() {
        containerId = docker.createAndStartContainer(ContainerRole.PREVIEW, 1L, "session-hints-it", 1L, null, null);
        writer.write(containerId, ContainerPaths.APP_DIR + "/index.html", """
                <form id="profile-form">
                  <div class="actions">
                    <button type="button" class="btn btn-secondary">취소</button>
                    <button type="submit" class="btn btn-primary">저장</button>
                  </div>
                </form>
                """);
        writer.write(containerId, ContainerPaths.APP_DIR + "/styles.css", """
                .btn { padding: 8px; }
                .btn-primary { background: blue; }
                """);
        writer.write(containerId, ContainerPaths.APP_DIR + "/node_modules/x/index.js", "btn-primary 저장\n");
    }

    @AfterEach
    void stop() {
        if (containerId != null) {
            docker.removeContainer(containerId);
        }
    }

    @Test
    void 저장_버튼의_마크업과_스타일_규칙을_찾고_꾸민_글은_실행되지_않는다() throws Exception {
        JsonNode save = json.readTree("""
                {"element":{"type":"button","text":"저장","selector":"#profile-form > div.actions > button.btn.btn-primary"},
                 "scopes":[{"kind":"element","type":"button","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}],"defaultScope":0}
                """);
        JsonNode hostile = json.readTree("""
                {"element":{"type":"p","text":"$(touch /tmp/pwned) `touch /tmp/pwned2` '; touch /tmp/pwned3 #","selector":"p"},
                 "scopes":[{"kind":"element","type":"p","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}],"defaultScope":0}
                """);

        List<List<JsonNode>> hints = finder.find(containerId, List.of(save, hostile));

        assertThat(hints.get(0)).extracting(h -> h.path("file").asText() + ":" + h.path("line").asInt())
                .containsExactly("index.html:4", "styles.css:2");
        assertThat(hints.get(0)).allMatch(h -> !h.path("file").asText().contains("node_modules"));
        assertThat(hints.get(1)).isEmpty();
        assertThat(docker.exec(containerId, "ls /tmp/pwned /tmp/pwned2 /tmp/pwned3 2>&1")).contains("No such file");
    }
}
