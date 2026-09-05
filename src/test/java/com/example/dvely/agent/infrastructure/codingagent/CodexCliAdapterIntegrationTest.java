package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the real Codex CLI through the real {@link CodingAgentContainerRunner} and
 * {@link CodexCliAdapter} — the production code path, production defaults, a real image, a real
 * key — and checks the one thing that matters: the agent actually edited the workspace.
 *
 * <p>Why this exists: a mocked test can only prove what we <i>send</i> to Docker. Whether the CLI
 * authenticates, whether its own sandbox lets it write, whether the container is cleaned up — those
 * are facts on the daemon's side and were exactly where the real defects were (Codex ignoring
 * {@code OPENAI_API_KEY}; its sandbox silently reporting success while writing nothing). This test
 * pins the hand-verified end-to-end so it cannot regress quietly.</p>
 *
 * <p>Gated like {@code LocalDbProvisionerIntegrationTest}: skipped unless {@code -Ddocker.it=true},
 * and additionally skipped (not failed) when no key is provided, so CI and keyless machines stay
 * green. The key is read from an environment variable, never a system property, so it does not
 * appear on any command line.</p>
 *
 * <pre>
 *   docker build -t qeploy/coding-agent:local docker/coding-agent
 *   QEPLOY_IT_OPENAI_API_KEY=sk-... ./gradlew test --no-daemon -Ddocker.it=true \
 *       --tests "*CodexCliAdapterIntegrationTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class CodexCliAdapterIntegrationTest {

    private static final String KEY_ENV = "QEPLOY_IT_OPENAI_API_KEY";
    private static final String AGENT_LABEL = "qeploy.codingAgent";

    private Path workspace;
    private DockerClient dockerClient;

    @BeforeEach
    void setUp() throws Exception {
        String key = System.getenv(KEY_ENV);
        assumeTrue(key != null && !key.isBlank(),
                KEY_ENV + " 가 비어 있어 실 Codex 통합 테스트를 건너뜁니다.");

        // /tmp is on Docker Desktop's default shared-path list; toRealPath() resolves the macOS
        // symlink to /private/tmp, which is the form the bind mount needs.
        workspace = Files.createTempDirectory(Path.of("/tmp"), "qeploy-codingagent-it-").toRealPath();
        Files.writeString(workspace.resolve("README.md"), "base\n");
        // Codex refuses to run outside a git repository (a deliberate recoverability guard);
        // Qeploy's real workspace is a clone, so mirror that here.
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=it@qeploy.test", "-c", "user.name=it", "commit", "-qm", "init");

        dockerClient = newDockerClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (workspace != null) {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (dockerClient != null) {
            dockerClient.close();
        }
    }

    @Test
    void codexAuthenticatesRunsAndActuallyWritesToTheWorkspaceThenCleansUp() {
        CodingAgentProperties properties = new CodingAgentProperties(); // production defaults
        CodingAgentContainerRunner runner = new CodingAgentContainerRunner(properties);
        CodexCliAdapter adapter = new CodexCliAdapter(runner, properties);

        CodingAgentResult result = adapter.run(new CodingAgentCommand(
                "Create a file named it-marker.txt in the current directory containing exactly: verified",
                workspace.toString(),
                System.getenv(KEY_ENV),
                Duration.ofMinutes(3)));

        // A non-zero exit or timeout here means auth or execution broke — the raw output is the
        // fastest diagnostic, so include it in the assertion message.
        assertThat(result.success())
                .withFailMessage("Codex 실행 실패 exit=%d timedOut=%s%nstdout:%n%s%nstderr:%n%s",
                        result.exitCode(), result.timedOut(), result.output(), result.errorOutput())
                .isTrue();

        // The defect this guards against was precisely "exit 0 but nothing written".
        Path marker = workspace.resolve("it-marker.txt");
        assertThat(marker).exists();
        assertThat(marker).content().isEqualToIgnoringNewLines("verified");

        // The runner removes its container in a finally block; a leftover would hold its memory
        // and pids reservation for as long as the daemon lives.
        List<Container> leftovers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(java.util.Map.of(AGENT_LABEL, "true"))
                .exec();
        assertThat(leftovers)
                .withFailMessage("코딩 에이전트 컨테이너가 정리되지 않았습니다: %s",
                        leftovers.stream().map(Container::getId).toList())
                .isEmpty();
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", workspace.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 실패: " + out);
        }
    }

    private static DockerClient newDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
        var http = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, http);
    }
}
