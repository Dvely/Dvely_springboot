package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 실제 Docker 로 프리뷰 백엔드 런타임 배선을 검증한다. 모의로는 "우리가 docker 에 무엇을 보내는지"만
 * 증명할 수 있고, "env 가 실제로 프로세스에 도달하는가 / 백그라운드 서버가 정말 3000 에 뜨는가 /
 * detector 가 실제 파일을 읽는가"는 데몬 쪽 사실이라 실측해야 한다 — 프리뷰 serve 버그가 정확히
 * 그 경계 너머에 있었다(코드는 성공인데 실제로는 옛 serve 가 응답).
 *
 * <p>LocalDbProvisionerIntegrationTest 와 같은 이유로 기본은 건너뛰고 로컬에서만 켠다:
 * {@code ./gradlew test --tests "*PreviewBackendRuntimeIntegrationTest" -Ddocker.it=true}
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class PreviewBackendRuntimeIntegrationTest {

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
        containerId = startNodeContainer();
        dockerService.exec(containerId, "mkdir -p " + APP_DIR);
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (RuntimeException ignored) {}
        }
    }

    /**
     * env 는 exec 프로세스에 도달하되, 우리가 넘긴 명령 문자열(=로그에 남는 값)에는 들어 있지 않아야
     * 한다. 이게 DB 비밀번호가 로그로 새지 않는 근거다.
     */
    @Test
    void envReachesTheProcessButIsNotInTheCommandString() {
        String secretValue = "s3cr3t-" + System.nanoTime();
        String command = "printf '%s' \"$INJECTED\"";   // 값이 아니라 변수 이름만 담긴다

        ExecResult result = dockerService.execWithExitCode(
                containerId, command, List.of("INJECTED=" + secretValue));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains(secretValue);   // 프로세스에는 도달
        assertThat(command).doesNotContain(secretValue);     // 로그에 남는 문자열에는 없음
    }

    /**
     * startNodeServer 가 앱 서버를 3000 에 실제로 띄우고(백그라운드로 살아남고), 주입한 env 를 그
     * 프로세스가 읽는지 — 서버 응답 본문에 주입값이 그대로 나오는지로 확인한다.
     */
    @Test
    void startNodeServerServesOn3000WithInjectedEnv() {
        // process.env.MARKER 를 그대로 돌려주는 최소 http 서버. PORT 도 env 로 읽어 3000 에 붙는다.
        String serverJs =
                "require('http').createServer((q,r)=>r.end(process.env.MARKER||'none'))"
                        + ".listen(process.env.PORT||0,'0.0.0.0')";
        writeFile(APP_DIR + "/server.js", serverJs);

        String marker = "hello-be-" + System.nanoTime();
        PreviewWorkspaceService workspace = new PreviewWorkspaceService(dockerService, null, null, null);

        workspace.startNodeServer(containerId, "node server.js",
                List.of("MARKER=" + marker, "PORT=3000"));

        // startNodeServer 가 리턴했다 = 3000 이 응답한다. 본문에 주입한 marker 가 그대로 나와야 한다.
        String body = null;
        for (int i = 0; i < 10; i++) {
            body = dockerService.exec(containerId,
                    "node -e \"require('http').get('http://127.0.0.1:3000',res=>{let d='';"
                            + "res.on('data',c=>d+=c);res.on('end',()=>process.stdout.write(d))})"
                            + ".on('error',()=>process.exit(1))\" 2>/dev/null");
            if (body != null && body.contains(marker)) break;
            sleep1s();
        }
        assertThat(body).contains(marker);
    }

    /** detector 가 클론된 실제 파일로 런타임 타입을 가른다. */
    @Test
    void detectorIdentifiesRuntimeFromRealFiles() {
        PreviewRuntimeDetector detector = new PreviewRuntimeDetector(dockerService);

        // build.gradle 있으면 JAVA_FULLSTACK
        writeFile(APP_DIR + "/build.gradle", "plugins { id 'java' }");
        assertThat(detector.detect(containerId)).isEqualTo(PreviewRuntimeType.JAVA_FULLSTACK);

        // gradle 제거 + express 서버 package.json 이면 NODE_SERVER
        dockerService.exec(containerId, "rm -f " + APP_DIR + "/build.gradle");
        writeFile(APP_DIR + "/package.json",
                "{\"scripts\":{\"start\":\"node server.js\"},\"dependencies\":{\"express\":\"^4\"}}");
        assertThat(detector.detect(containerId)).isEqualTo(PreviewRuntimeType.NODE_SERVER);

        // 아무것도 없으면 STATIC
        dockerService.exec(containerId, "rm -f " + APP_DIR + "/package.json");
        assertThat(detector.detect(containerId)).isEqualTo(PreviewRuntimeType.STATIC);
    }

    // ── helpers ───────────────────────────────────────────────

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
        // base64 로 감싸 셸 따옴표/특수문자 문제를 피한다.
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dockerService.exec(containerId,
                "echo '" + b64 + "' | base64 -d > " + path);
    }

    private void sleep1s() {
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
