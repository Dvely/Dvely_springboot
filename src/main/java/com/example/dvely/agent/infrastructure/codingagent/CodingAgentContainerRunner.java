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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * <p><b>Why the key travels in the exec environment:</b> env is passed via
 * {@code ExecCreateCmd#withEnv} rather than baked into the container config or the command, so the
 * credential does not appear in {@code docker inspect} of the container, in the logged command, or
 * in an exception message.</p>
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
     * @param hostWorkspaceDir absolute host path of the checkout, bind-mounted read-write
     * @param argv             the command to run, already split into arguments (no shell)
     * @param env              {@code KEY=VALUE} entries injected into the exec only
     * @param timeout          wall-clock bound; on expiry the container is killed
     */
    public ContainerRunOutcome run(String hostWorkspaceDir,
                                   List<String> argv,
                                   List<String> env,
                                   Duration timeout) {
        assertImagePresent();

        String containerId = createAndStart(hostWorkspaceDir);
        try {
            return exec(containerId, argv, env, timeout);
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

    private ContainerRunOutcome exec(String containerId,
                                     List<String> argv,
                                     List<String> env,
                                     Duration timeout) {
        // Only the executable name is logged. argv carries the prompt (which can contain source
        // code) and env carries the API key; neither belongs in a log line.
        log.debug("코딩 에이전트 exec: containerId={} cmd={}", containerId, argv.isEmpty() ? "?" : argv.getFirst());

        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withEnv(env == null || env.isEmpty() ? null : List.copyOf(env))
                .withCmd(argv.toArray(String[]::new))
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
