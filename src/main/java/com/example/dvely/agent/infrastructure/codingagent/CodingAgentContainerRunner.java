package com.example.dvely.agent.infrastructure.codingagent;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs one command inside a throwaway, isolated container built from the coding-agent image.
 *
 * <p><b>Why this does not reuse {@code DockerContainerService}:</b> that class encodes the
 * <i>preview</i> container policy — a published port on loopback, the shared {@code qeploy-preview}
 * bridge, a fixed 10-minute exec bound. A coding-agent run publishes no port, wants its own
 * lifetime bound, and must not share a network with preview sessions. Keeping this separate also
 * keeps the coding-agent unit off a file the provisioning/preview work edits frequently.</p>
 *
 * <p><b>Why argv and not a shell string:</b> the command is handed to Docker as an argument vector,
 * never through {@code sh -c}. A prompt is arbitrary user text; interpolating it into a shell line
 * would be a command-injection hole, and quoting it correctly is the kind of thing that is right
 * until the day it isn't. With argv there is no shell to inject into.</p>
 *
 * <p><b>Why the key never rides on a docker-java command object:</b> both credential shapes stage
 * the secret as a short-lived file via the archive API and let a constant shell wrapper pick it up.
 * The obvious alternative — {@code ExecCreateCmd#withEnv} — leaks: docker-java 3.7.1's
 * {@code AbstrDockerCmd.exec()} logs {@code LOGGER.debug("Cmd: {}", this)} and its
 * {@code toString()} is a reflective dump of every field, so with
 * {@code logging.level.com.github.dockerjava=DEBUG} the key appears in the application log
 * verbatim (reproduced against 3.7.1 + logback). Staging keeps the secret out of every field that
 * dump can reach; it is never in the container config, the exec's env, argv, or an exception
 * message. The prompt still rides in {@code cmd} and would appear at DEBUG, which is why
 * {@code application.yaml} also pins that logger to WARN.</p>
 */
@Slf4j
@Component
public class CodingAgentContainerRunner {

    private static final String AGENT_LABEL = "qeploy.codingAgent";

    private final DockerClient dockerClient;
    private final CodingAgentProperties properties;

    // Explicit @Autowired because the test seam below makes this a two-constructor bean, and
    // Spring will not guess between them.
    @Autowired
    public CodingAgentContainerRunner(CodingAgentProperties properties) {
        this.properties = properties;
        String dockerHost = System.getProperty("os.name").toLowerCase().contains("win")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        var httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /** Test seam, mirroring {@code DockerContainerService}'s package-private constructor. */
    CodingAgentContainerRunner(DockerClient dockerClient, CodingAgentProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    /**
     * How a vendor's CLI is handed the end user's key. The two shapes exist because the CLIs
     * genuinely differ, not as a convenience: Codex ignores {@code OPENAI_API_KEY} and only accepts
     * a key through {@code codex login --with-api-key} on stdin (measured against codex-cli
     * 0.153.2, where the env route returns "401 Missing bearer"), while Claude Code reads
     * {@code ANTHROPIC_API_KEY} from its environment.
     *
     * <p>Both are delivered by staging a file, never by a docker-java command field — see the class
     * javadoc for why that distinction matters.</p>
     */
    public sealed interface Credential {

        /** Run {@code argv} first with the secret as its stdin, then run the agent. */
        record LoginCommand(List<String> argv, String secret) implements Credential {}

        /** Export the secret as {@code name} into the agent's own process environment. */
        record EnvVar(String name, String secret) implements Credential {}
    }

    /**
     * @param hostWorkspaceDir absolute host path of the checkout, bind-mounted read-write
     * @param credential       how to hand the CLI its key, or {@code null} for none
     * @param argv             the command to run, already split into arguments (no shell)
     * @param timeout          wall-clock bound; on expiry the container is killed
     */
    public ContainerRunOutcome run(String hostWorkspaceDir,
                                   Credential credential,
                                   List<String> argv,
                                   Duration timeout) {
        assertImagePresent();

        String containerId = createAndStart(hostWorkspaceDir);
        try {
            return switch (credential) {
                case Credential.LoginCommand login -> {
                    ContainerRunOutcome auth =
                            exec(containerId, login.argv(), login.secret(), null, timeout);
                    // Failing here rather than running the agent anyway: without a credential the
                    // agent would burn its whole timeout retrying a 401, and the resulting output
                    // would blame the model instead of the missing key.
                    yield (auth.timedOut() || auth.exitCode() != 0)
                            ? auth
                            : exec(containerId, argv, null, null, timeout);
                }
                case Credential.EnvVar env ->
                        exec(containerId, argv, env.secret(), env.name(), timeout);
                case null -> exec(containerId, argv, null, null, timeout);
            };
        } finally {
            removeQuietly(containerId);
        }
    }

    /**
     * The image is built locally and never pulled. Pulling on absence would mean that a typo in
     * the configured name, or a public image squatting that name, gets to execute with a user's
     * real API key in its environment — so a missing image is a hard, explicit failure instead.
     */
    private void assertImagePresent() {
        try {
            dockerClient.inspectImageCmd(properties.getImage()).exec();
        } catch (NotFoundException e) {
            throw new CodingAgentProvisionException(
                    "코딩 에이전트 이미지가 없습니다: " + properties.getImage()
                            + " — docker/coding-agent/Dockerfile 로 먼저 빌드해주세요.", e);
        }
    }

    private String createAndStart(String hostWorkspaceDir) {
        try {
            // Isolation mirrors the preview policy (BI-194) minus the parts that only make sense
            // for a served preview: no exposed port, and no shared bridge network. Capabilities
            // are dropped to the set npm's lifecycle scripts need, privilege escalation is off,
            // and memory/CPU/pids are capped so a runaway agent cannot starve the host.
            CreateContainerResponse container = dockerClient.createContainerCmd(properties.getImage())
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(new Bind(hostWorkspaceDir,
                                    new Volume(properties.getWorkspaceMountPath())))
                            .withMemory(properties.getMemoryBytes())
                            .withMemorySwap(properties.getMemoryBytes())
                            .withNanoCPUs(properties.getNanoCpus())
                            .withPidsLimit(properties.getPidsLimit())
                            .withCapDrop(Capability.ALL)
                            .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID)
                            .withSecurityOpts(List.of("no-new-privileges")))
                    .withLabels(Map.of(AGENT_LABEL, "true"))
                    .withWorkingDir(properties.getWorkspaceMountPath())
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            return container.getId();
        } catch (CodingAgentProvisionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodingAgentProvisionException("코딩 에이전트 컨테이너 생성에 실패했습니다.", e);
        }
    }

    /**
     * Where a step's stdin payload is staged inside the container. Under {@code /run} so it lives
     * on the container's own filesystem (never the bind-mounted workspace) and dies with it.
     */
    private static final String STDIN_REMOTE_DIR = "/run";
    private static final String STDIN_ENTRY = "qeploy/stdin";
    private static final String STDIN_PATH = STDIN_REMOTE_DIR + "/" + STDIN_ENTRY;

    /**
     * Constant wrapper that feeds the staged file to the real command as stdin, then deletes it.
     *
     * <p>The command's argv is passed as positional parameters and expanded with {@code "$@"} —
     * it is never interpolated into this string, so a prompt or flag can contain any shell
     * metacharacter without becoming syntax. Only this fixed text is ever parsed by the shell.</p>
     */
    private static final String STDIN_WRAPPER =
            "\"$@\" < " + STDIN_PATH + "; s=$?; rm -f " + STDIN_PATH + "; exit $s";

    /** Env var names we will interpolate into a shell wrapper. Anything else is rejected. */
    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Constant-shaped wrapper that reads the staged file into {@code name}, deletes the file, and
     * execs the real command. Only {@code name} is interpolated, and it is validated against
     * {@link #ENV_NAME} first — argv still rides as positional parameters, never as text.
     */
    private static String envWrapper(String name) {
        if (!ENV_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("환경변수 이름이 올바르지 않습니다: " + name);
        }
        return name + "=\"$(cat " + STDIN_PATH + ")\"; rm -f " + STDIN_PATH
                + "; export " + name + "; exec \"$@\"";
    }

    /**
     * @param secret  value to stage into the container, or {@code null} for no credential
     * @param envName when non-null, the secret is exported under this name for {@code argv};
     *                when null, it is redirected into {@code argv}'s stdin instead
     */
    private ContainerRunOutcome exec(String containerId,
                                     List<String> argv,
                                     String secret,
                                     String envName,
                                     Duration timeout) {
        // Only the executable name is logged. argv carries the prompt (which can contain source
        // code) and the secret is staged separately; neither belongs in a log line.
        log.debug("코딩 에이전트 exec: containerId={} cmd={}", containerId, argv.isEmpty() ? "?" : argv.getFirst());

        // The secret is delivered by staging a file, never by ExecStartCmd#withStdIn or
        // ExecCreateCmd#withEnv. withStdIn does not work at all on this codebase's OkHttp
        // transport (measured: it closes its half of the hijacked stream and the response reader
        // dies with AsynchronousCloseException mid-command). withEnv works but leaks — docker-java
        // reflectively dumps every command field into a DEBUG log line. The archive upload is an
        // ordinary HTTP PUT with no hijacking and no field to dump.
        List<String> command = argv;
        if (secret != null) {
            stageSecret(containerId, secret);
            String wrapper = envName == null ? STDIN_WRAPPER : envWrapper(envName);
            List<String> wrapped = new ArrayList<>(List.of("sh", "-c", wrapper, "sh"));
            wrapped.addAll(argv);
            command = wrapped;
        }

        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withAttachStdin(false)
                .withCmd(command.toArray(String[]::new))
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        boolean completed;
        try {
            completed = dockerClient.execStartCmd(execCreate.getId())
                    .withDetach(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            ByteArrayOutputStream sink =
                                    frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
                            try {
                                sink.write(frame.getPayload());
                            } catch (Exception ignored) {
                                // A failed capture must not abort the run; the exit code still decides.
                            }
                        }
                    })
                    .awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("코딩 에이전트 실행이 인터럽트되었습니다.", e);
        }

        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);

        if (!completed) {
            // The container is removed by the caller's finally block, which kills the process
            // group with it — that is what actually stops the run, not this return value.
            return new ContainerRunOutcome(-1, out, err, true);
        }

        Long exitCode = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
        return new ContainerRunOutcome(exitCode == null ? -1 : exitCode.intValue(), out, err, false);
    }

    /**
     * Uploads the secret as {@code /run/qeploy/stdin} (mode 0600) using the archive API. The tar
     * carries the {@code qeploy/} directory prefix so Docker creates the subdirectory itself — no
     * extra exec is needed just to {@code mkdir}.
     */
    private void stageSecret(String containerId, String stdin) {
        byte[] payload = stdin.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            TarArchiveEntry entry = new TarArchiveEntry(STDIN_ENTRY);
            entry.setSize(payload.length);
            entry.setMode(0600);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        } catch (IOException e) {
            throw new CodingAgentProvisionException("stdin 페이로드 tar 생성에 실패했습니다.", e);
        }
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath(STDIN_REMOTE_DIR)
                .withTarInputStream(new ByteArrayInputStream(buffer.toByteArray()))
                .exec();
    }

    private void removeQuietly(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (RuntimeException e) {
            // Cleanup failure must not mask the run's own outcome; a leaked container is visible
            // by its qeploy.codingAgent label.
            log.warn("코딩 에이전트 컨테이너 정리 실패: containerId={} reason={}", containerId, e.toString());
        }
    }

    /** One container run's raw outcome, before it is interpreted as a coding-agent result. */
    public record ContainerRunOutcome(int exitCode, String stdout, String stderr, boolean timedOut) {
    }
}
