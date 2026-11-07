package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 실제 프리뷰 컨테이너에 {@code write_file} 을 써 본다(#617 완료 기준).
 *
 * <p>목 테스트는 무엇을 보내는지까지만 증명한다. 소유자가 node 로 맞춰지는지, 도커가 디렉터리를 파일로 덮지
 * 않는지, 예전 방식이 막히던 크기(원문 97KiB~)가 실제로 들어가는지는 데몬만 말해준다.</p>
 *
 * <pre>./gradlew test --tests "*ContainerFileWriterDockerIntegrationTest" -Ddocker.it=true</pre>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class ContainerFileWriterDockerIntegrationTest {

    private final DockerContainerService service = new DockerContainerService();
    private final ContainerFileWriter writer = new ContainerFileWriter(service);
    private String containerId;

    @BeforeEach
    void startPreviewContainer() {
        containerId = service.createAndStartContainer(
                ContainerRole.PREVIEW, 1L, "session-write-it", 1L, null, null);
    }

    @AfterEach
    void removeContainer() {
        if (containerId != null) {
            service.removeContainer(containerId);
        }
    }

    @Test
    void writesA200KibFileOwnedByNodeAndOverwritesIt() {
        // 예전 방식은 base64 가 sh -c 인자 하나(128KiB 상한)에 들어가 원문 97KiB 부터 실패했다.
        String large = "x".repeat(200 * 1024 - 1) + "\n";
        String path = ContainerPaths.APP_DIR + "/src/big.js";

        writer.write(containerId, path, large);

        assertThat(sh("wc -c < " + path).strip()).isEqualTo(String.valueOf(large.getBytes(StandardCharsets.UTF_8).length));
        assertThat(sh("stat -c %U:%G " + path).strip()).isEqualTo("node:node");
        // 새로 만든 상위 디렉터리도 node 소유여야 그 뒤 에이전트가 거기에 쓸 수 있다.
        assertThat(sh("stat -c %U " + ContainerPaths.APP_DIR + "/src").strip()).isEqualTo("node");

        writer.write(containerId, path, "export default 1;\n");

        assertThat(sh("cat " + path)).isEqualTo("export default 1;\n");
        assertThat(sh("stat -c %U " + path).strip()).isEqualTo("node");
    }

    @Test
    void nonAsciiContentAndFileNamesRoundTrip() {
        String path = ContainerPaths.APP_DIR + "/페이지/소개.md";
        String content = "# 소개\n한글과 emoji 😀\n";

        writer.write(containerId, path, content);

        assertThat(sh("cat '" + path + "'")).isEqualTo(content);
    }

    @Test
    void overwritingKeepsTheExecutableBit() {
        String script = ContainerPaths.APP_DIR + "/run.sh";
        sh("printf 'echo old\\n' > " + script + " && chmod 755 " + script);

        writer.write(containerId, script, "echo new\n");

        assertThat(sh("stat -c %a " + script).strip()).isEqualTo("755");
        assertThat(sh(script).strip()).isEqualTo("new");
    }

    @Test
    void neverReplacesAnExistingDirectoryWithAFile() {
        String dir = ContainerPaths.APP_DIR + "/src";
        sh("mkdir -p " + dir + " && echo keep > " + dir + "/App.jsx");

        assertThatThrownBy(() -> writer.write(containerId, dir, "oops"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("디렉터리가 있어");

        assertThat(sh("cat " + dir + "/App.jsx").strip()).isEqualTo("keep");
    }

    @Test
    void aDirectoryTheContainerUserCannotCreateIsReportedNotSwallowed() {
        // 쓰기 권한을 닫은 디렉터리 밑에는 node 가 하위 디렉터리를 만들지 못한다. 예전에는 이 실패를 보지 않았다.
        sh("mkdir -p " + ContainerPaths.APP_DIR + "/locked && chmod 555 " + ContainerPaths.APP_DIR + "/locked");

        assertThatThrownBy(() -> writer.write(containerId, ContainerPaths.APP_DIR + "/locked/sub/a.js", "x"))
                .isInstanceOf(ContainerFileWriteException.class)
                .hasMessageContaining("디렉터리를 만들지 못했습니다");
    }

    private String sh(String command) {
        ExecResult result = service.execWithExitCode(containerId, command);
        assertThat(result.succeeded()).as("%s → %s", command, result.output()).isTrue();
        return result.output();
    }
}
