package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * JAVA_FULLSTACK 의 novel 한 조각 — 부팅 apk 로 JDK·nginx 를 얹고, 내부 nginx 가 3000 에서
 * {@code /api}→8080, 나머지→정적 FE 로 실제로 가르는지 — 를 실측 Docker 로 검증한다.
 *
 * <p>전체 흐름의 gradle bootRun 은 배포 다운로드+컴파일로 수 분이 걸려 테스트에 담지 않는다. 대신
 * 그 자리를 최소 stub BE(node http 서버 8080)로 대체해 라우팅 자체를 고정한다 — "코드는 성공인데
 * 실제로는 안 갈린다"를 막는 것이 목적이다. 기본은 건너뛰고 로컬에서만 켠다:
 * {@code ./gradlew test --tests "*PreviewJavaFullstackIntegrationTest" -Ddocker.it=true}
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class PreviewJavaFullstackIntegrationTest {

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
        containerId = startNodeContainer();
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (RuntimeException ignored) {}
        }
    }

    /**
     * 한 컨테이너에서: (1) apk 로 JDK 가 실제로 얹혀 java 가 돌고, (2) 내부 nginx 가 3000 에서
     * /api→BE(8080), 나머지→FE 정적으로 갈린다.
     */
    @Test
    void apkInstallsJdkAndInternalNginxRoutesApiAndStatic() {
        // node:20-alpine 위에 JDK + nginx 를 얹는다(프리뷰 부팅 apk 와 같은 방식).
        dockerService.exec(containerId, "apk add --no-cache openjdk21 nginx 2>&1 | tail -n 3");

        // (1) JDK 가 실제로 설치돼 java 가 돈다.
        DockerContainerService.ExecResult java = dockerService.execWithExitCode(containerId, "java -version");
        assertThat(java.succeeded()).isTrue();
        assertThat(java.output()).containsIgnoringCase("openjdk");

        // gradle bootRun 대신 최소 stub BE 를 8080 에 띄운다 — 라우팅만 검증한다.
        writeFile("/tmp/be.js",
                "require('http').createServer((q,r)=>r.end('backend-ok')).listen(8080,'127.0.0.1')");
        dockerService.exec(containerId, "nohup node /tmp/be.js > /tmp/be.log 2>&1 &");

        // FE 정적 산출물.
        dockerService.exec(containerId, "mkdir -p /tmp/fe && chmod 755 /tmp/fe");
        writeFile("/tmp/fe/index.html", "frontend-ok");
        dockerService.exec(containerId, "chmod 644 /tmp/fe/index.html");

        // 실제 메서드로 내부 nginx 를 3000 에 띄운다.
        PreviewWorkspaceService workspace = new PreviewWorkspaceService(dockerService, null, null, null);
        workspace.startInternalNginxRouter(containerId, "/api", "/tmp/fe");

        // (2) 3000/api/* → BE, 3000/ → FE.
        assertThat(httpGet("http://127.0.0.1:3000/api/ping")).contains("backend-ok");
        assertThat(httpGet("http://127.0.0.1:3000/")).contains("frontend-ok");
    }

    // ── helpers ───────────────────────────────────────────────

    private String httpGet(String url) {
        String body = null;
        for (int i = 0; i < 10; i++) {
            body = dockerService.exec(containerId,
                    "node -e \"require('http').get('" + url + "',res=>{let d='';"
                            + "res.on('data',c=>d+=c);res.on('end',()=>process.stdout.write(d))})"
                            + ".on('error',()=>process.exit(1))\" 2>/dev/null");
            if (body != null && !body.isBlank()) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return body == null ? "" : body;
    }

    private String startNodeContainer() {
        var c = dockerClient.createContainerCmd("node:20-alpine")
                .withHostConfig(HostConfig.newHostConfig()
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID))
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        dockerClient.startContainerCmd(c.getId()).exec();
        return c.getId();
    }

    private void writeFile(String path, String content) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        dockerService.exec(containerId, "echo '" + b64 + "' | base64 -d > " + path);
    }
}
