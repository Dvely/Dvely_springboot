package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * EC2 배포 빌드 경로의 novel 한 두 조각을 실측 Docker 로 검증한다 — AWS 없이:
 * (1) {@link DockerContainerService#copyFileFromContainer} 가 컨테이너의 파일(빌드 jar)을 tar
 *     스트림에서 정확히 꺼내는지, (2) NativeBuildService.buildJar 의 find 명령이 alpine(busybox)
 *     find 에서 실행가능 jar 만 집고 {@code -plain.jar}/{@code -sources.jar} 를 빼는지.
 *
 * <p>전체 {@code ./gradlew build} 는 배포 다운로드+컴파일로 수 분이 걸려 담지 않는다(같은 이유로
 * PreviewJavaFullstackIntegrationTest 도 bootRun 을 stub 으로 대체) — 그건 실제 AWS e2e 에서 진짜
 * 레포로 검증한다. 여기서는 우리가 새로 쓴 추출·탐색 메커니즘만 고정한다. 기본은 건너뛰고 로컬에서만:
 * {@code ./gradlew test --tests "*BackendBuildPathIntegrationTest" -Ddocker.it=true}</p>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class BackendBuildPathIntegrationTest {

    private static final String APP_DIR = "/workspace/app";

    private DockerClient dockerClient;
    private DockerContainerService dockerService;
    private String containerId;

    @BeforeEach
    void setUp() {
        String dockerHost = System.getProperty("os.name").toLowerCase().contains("win")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost).build();
        var httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).build();
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
        dockerService = new DockerContainerService();
        var c = dockerClient.createContainerCmd("node:20-alpine")
                .withHostConfig(HostConfig.newHostConfig()
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID))
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        dockerClient.startContainerCmd(c.getId()).exec();
        containerId = c.getId();
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (RuntimeException ignored) {}
        }
    }

    @Test
    void copyFileFromContainerExtractsExactContent() throws Exception {
        String content = "qeploy-jar-marker-éñ-12345\nsecond-line\n";   // 멀티라인+비ASCII 로 tar 추출 정확도 확인
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId, "echo '" + b64 + "' | base64 -d > /tmp/app.jar");

        Path dest = Files.createTempFile("extracted-", ".jar");
        try {
            dockerService.copyFileFromContainer(containerId, "/tmp/app.jar", dest);
            assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo(content);
        } finally {
            Files.deleteIfExists(dest);
        }
    }

    @Test
    void locateJarCommandPicksRunnableJarOnly() {
        dockerService.exec(containerId, "mkdir -p " + APP_DIR + "/build/libs");
        dockerService.exec(containerId,
                "cd " + APP_DIR + "/build/libs && touch app.jar app-plain.jar app-sources.jar app-javadoc.jar");

        // NativeBuildService.buildJar 와 동일한 명령 — alpine(busybox) find 에서 실제로 동작하는지 고정.
        String jar = dockerService.exec(containerId,
                "find " + APP_DIR + " -path '*/build/libs/*.jar' ! -name '*-plain.jar' "
                        + "! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1").trim();

        assertThat(jar).endsWith("/build/libs/app.jar");
        assertThat(jar).doesNotContain("plain").doesNotContain("sources").doesNotContain("javadoc");
    }
}
