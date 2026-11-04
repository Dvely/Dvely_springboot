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
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the real Claude Code CLI through the real {@link CodingAgentContainerRunner} and
 * {@link ClaudeCodeCliAdapter} with production argv defaults, a real image and a real key (#596).
 *
 * <p>These facts live on the CLI's side, so a mocked test can only prove what we send: the agent
 * actually edits the workspace (a {@code -p} run without a permission mode denies edits), the
 * repository's own {@code .claude/settings.json} hooks and {@code .mcp.json} servers do not run (a
 * {@code -p} run without {@code --bare} loads them with no trust prompt), and a run stopped by the
 * spend cap is not reported as a success. The hook and the MCP server here only touch a marker
 * file. Each case is one paid call.</p>
 *
 * <p>Gated like {@link CodexCliAdapterIntegrationTest}: skipped unless {@code -Ddocker.it=true}, and
 * skipped (not failed) when no key is provided. The key is read from an environment variable so it
 * never appears on a command line. The model defaults to the cheapest tier; the behaviour under test
 * does not depend on it.</p>
 *
 * <pre>
 *   docker build --build-arg CLAUDE_CODE_VERSION=2.1.316 -t qeploy/coding-agent:local docker/coding-agent
 *   QEPLOY_IT_ANTHROPIC_API_KEY=sk-ant-... ./gradlew test --no-daemon -Ddocker.it=true \
 *       --tests "*ClaudeCodeCliAdapterIntegrationTest"
 * </pre>
 *
 * <p>An Anthropic-compatible endpoint (for example GLM's) can stand in for the API with
 * {@code QEPLOY_IT_ANTHROPIC_BASE_URL}; the key variable then holds that provider's key.</p>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class ClaudeCodeCliAdapterIntegrationTest {

    private static final String KEY_ENV = "QEPLOY_IT_ANTHROPIC_API_KEY";
    private static final String MODEL_ENV = "QEPLOY_IT_CLAUDE_MODEL";
    private static final String BASE_URL_ENV = "QEPLOY_IT_ANTHROPIC_BASE_URL";
    private static final String AGENT_LABEL = "qeploy.codingAgent";
    private static final String HOOK_MARKER = ".qeploy-it-hook";
    private static final String MCP_MARKER = ".qeploy-it-mcp";

    private Path workspace;
    private DockerClient dockerClient;

    @BeforeEach
    void setUp() throws Exception {
        String key = System.getenv(KEY_ENV);
        assumeTrue(key != null && !key.isBlank(),
                KEY_ENV + " 가 비어 있어 실 Claude Code 통합 테스트를 건너뜁니다.");

        workspace = Files.createTempDirectory(Path.of("/tmp"), "qeploy-claude-it-").toRealPath();
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

    /**
     * The production layout: {@code CodingAgentWorkspaceBridge} copies the project to
     * {@code <workspace>/app} and the CLI runs with {@code /workspace} as its working directory.
     */
    @Test
    void inTheProductionLayoutTheAgentEditsTheProjectWithoutRunningItsHooksOrMcpServers() throws Exception {
        Path app = Files.createDirectories(workspace.resolve("app"));
        writeRepository(app);

        CodingAgentResult result = run(properties(), "In the app directory, create a file named it-marker.txt containing exactly: verified");

        assertEditedWithoutRepositoryConfig(result, app.resolve("it-marker.txt"));
    }

    /**
     * The case the CLI documentation describes: repository configuration in the working directory
     * itself. Kept so the guarantee does not depend on where the bridge happens to put the project.
     */
    @Test
    void repositoryConfigurationInTheWorkingDirectoryDoesNotRunEither() throws Exception {
        writeRepository(workspace);

        CodingAgentResult result = run(properties(), "Create a file named it-marker.txt in the current directory containing exactly: verified");

        assertEditedWithoutRepositoryConfig(result, workspace.resolve("it-marker.txt"));
    }

    /**
     * A run stopped by the spend cap must not look like a successful one — the bridge would copy a
     * half-finished workspace back and show it as the result (#572).
     */
    @Test
    void aRunStoppedByTheSpendCapIsReportedAsAFailure() throws Exception {
        Path app = Files.createDirectories(workspace.resolve("app"));
        writeRepository(app);
        CodingAgentProperties properties = properties();
        properties.getClaude().setMaxBudgetUsd("0.0001");

        CodingAgentResult result = run(properties, "In the app directory, create a file named it-marker.txt containing exactly: verified");

        assertThat(result.success())
                .withFailMessage("예산 상한에 걸린 실행이 성공으로 보고됐다 exit=%d%nstdout:%n%s%nstderr:%n%s",
                        result.exitCode(), result.output(), result.errorOutput())
                .isFalse();
    }

    private CodingAgentProperties properties() {
        CodingAgentProperties properties = new CodingAgentProperties(); // production argv defaults
        properties.getClaude().setModel(System.getenv().getOrDefault(MODEL_ENV, "haiku"));
        String baseUrl = System.getenv(BASE_URL_ENV);
        if (baseUrl != null && !baseUrl.isBlank()) {
            // Anthropic 호환 엔드포인트(예: GLM)로 돌릴 때만 — 권한·훅·MCP 판정은 CLI 쪽 동작이라 백엔드와 무관하다.
            // 운영 경로는 api.anthropic.com 만 쓰므로, 그 호스트를 egress 허용목록에 더하고 주소는 --settings 로 준다.
            List<String> hosts = new java.util.ArrayList<>(properties.getEgress().getAllowedHosts());
            hosts.add(java.net.URI.create(baseUrl.strip()).getHost());
            properties.getEgress().setAllowedHosts(hosts);
            List<String> argv = new java.util.ArrayList<>(properties.getClaude().getArgvPrefix());
            argv.addAll(List.of("--settings", "{\"env\":{\"ANTHROPIC_BASE_URL\":\"" + baseUrl.strip() + "\"}}"));
            properties.getClaude().setArgvPrefix(argv);
        }
        return properties;
    }

    private CodingAgentResult run(CodingAgentProperties properties, String prompt) {
        CodingAgentContainerRunner runner = new CodingAgentContainerRunner(properties);
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter(runner, properties);
        CodingAgentResult result = adapter.run(new CodingAgentCommand(
                prompt, workspace.toString(), System.getenv(KEY_ENV), Duration.ofMinutes(3)));

        List<Container> leftovers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(java.util.Map.of(AGENT_LABEL, "true"))
                .exec();
        assertThat(leftovers)
                .withFailMessage("코딩 에이전트 컨테이너가 정리되지 않았습니다: %s",
                        leftovers.stream().map(Container::getId).toList())
                .isEmpty();
        return result;
    }

    private void assertEditedWithoutRepositoryConfig(CodingAgentResult result, Path marker) throws IOException {
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(result.success())
                .withFailMessage("Claude Code 실행 실패 exit=%d timedOut=%s%nstdout:%n%s%nstderr:%n%s",
                        result.exitCode(), result.timedOut(), result.output(), result.errorOutput())
                .isTrue();
        soft.assertThat(marker)
                .withFailMessage("요청한 파일이 생기지 않았다(편집이 거부됐을 수 있다)%nstdout:%n%s", result.output())
                .exists();
        soft.assertThat(workspace.resolve(HOOK_MARKER))
                .withFailMessage("저장소의 .claude/settings.json 훅이 실행됐다")
                .doesNotExist();
        soft.assertThat(workspace.resolve(MCP_MARKER))
                .withFailMessage("저장소의 .mcp.json 서버가 실행됐다")
                .doesNotExist();
        soft.assertAll();
        assertThat(marker).content().isEqualToIgnoringNewLines("verified");
    }

    /** 저장소가 가진 프로젝트 설정 — 실행되면 마운트 루트(/workspace)에 표식 파일만 남긴다. */
    private void writeRepository(Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "base\n");
        Files.createDirectories(dir.resolve(".claude"));
        Files.writeString(dir.resolve(".claude/settings.json"), """
                {
                  "hooks": {
                    "SessionStart": [{"hooks": [{"type": "command", "command": "touch /workspace/%1$s"}]}],
                    "PreToolUse": [{"matcher": "*", "hooks": [{"type": "command", "command": "touch /workspace/%1$s"}]}]
                  }
                }
                """.formatted(HOOK_MARKER));
        Files.writeString(dir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "qeploy-it-marker": {"command": "sh", "args": ["-c", "touch /workspace/%s"]}
                  }
                }
                """.formatted(MCP_MARKER));
        git(dir, "init", "-q");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=it@qeploy.test", "-c", "user.name=it", "commit", "-qm", "init");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
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
