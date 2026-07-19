package com.example.dvely.agent.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DockerContainerService {

    private static final String IMAGE          = "node:20-alpine";
    private static final int    CONTAINER_PORT = 3000;
    private static final long   EXEC_TIMEOUT_MIN = 10L;
    private static final String AGENT_LABEL = "qeploy.agent";
    private static final String USER_ID_LABEL = "qeploy.userId";
    private static final String PREVIEW_SESSION_ID_LABEL = "qeploy.previewSessionId";
    private static final String PROJECT_ID_LABEL = "qeploy.projectId";
    private static final String CONVERSATION_ID_LABEL = "qeploy.conversationId";
    private static final String TASK_ID_LABEL = "qeploy.taskId";
    private static final String LEGACY_AGENT_LABEL = "dvely.agent";

    // --- Preview container isolation policy (BI-194). Kept as plain constants rather than
    // configuration properties, matching the existing IMAGE/CONTAINER_PORT style above — this
    // becomes a @ConfigurationProperties surface only once a concrete need to tune it appears.
    private static final long MEMORY_LIMIT_BYTES = 1L << 30; // 1 GiB: dev server + npm install headroom
    // Swap == memory (no extra swap): letting a container swap past its memory limit would hide
    // an OOM behind slow disk I/O instead of a clean, visible kill (surfaced via oomKilled below).
    private static final long MEMORY_SWAP_LIMIT_BYTES = MEMORY_LIMIT_BYTES;
    private static final long NANO_CPUS = 1_000_000_000L; // 1.0 vCPU per session, fair-share
    private static final long PIDS_LIMIT = 256L; // fork-bomb guard; ~4x observed npm install process counts
    private static final String PREVIEW_NETWORK_NAME = "qeploy-preview";
    // one-shot `stats` needs ~1s to sample a CPU delta (see getContainerStats); 3s is the
    // point past which we degrade the /status response instead of blocking the caller.
    private static final long STATS_TIMEOUT_SECONDS = 3L;
    private static final long LOGS_TIMEOUT_SECONDS = 10L;

    private final DockerClient dockerClient;

    public DockerContainerService() {
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

    DockerContainerService(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(Long userId,
                                          String previewSessionId,
                                          Long projectId,
                                          Long conversationId,
                                          String taskId) {
        pullImageIfNeeded();
        ensurePreviewNetwork();

        ExposedPort exposedPort = ExposedPort.tcp(CONTAINER_PORT);
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindPort(0));

        Map<String, String> labels = new HashMap<>();
        labels.put(AGENT_LABEL, "true");
        labels.put(USER_ID_LABEL, String.valueOf(userId));
        putLabel(labels, PREVIEW_SESSION_ID_LABEL, previewSessionId);
        putLabel(labels, PROJECT_ID_LABEL, projectId);
        putLabel(labels, CONVERSATION_ID_LABEL, conversationId);
        putLabel(labels, TASK_ID_LABEL, taskId);

        // Isolation policy (BI-194): memory+swap cap with a visible OOM signal, a fair CPU
        // share, a pids ceiling against fork bombs, a minimal capability set (only what npm's
        // lifecycle scripts need to drop privileges to `nobody` and chown files), no privilege
        // escalation, and a dedicated bridge network with inter-container communication
        // disabled. Rootfs stays read-write (the agent writes project files into the container)
        // and no restart policy is set (a dead container surfaces via the status API instead).
        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(portBindings)
                        .withMemory(MEMORY_LIMIT_BYTES)
                        .withMemorySwap(MEMORY_SWAP_LIMIT_BYTES)
                        .withNanoCPUs(NANO_CPUS)
                        .withPidsLimit(PIDS_LIMIT)
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID)
                        .withSecurityOpts(List.of("no-new-privileges"))
                        .withNetworkMode(PREVIEW_NETWORK_NAME))
                .withLabels(labels)
                .withCmd("tail", "-f", "/dev/null")
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Docker 컨테이너 시작: id={} userId={}", container.getId(), userId);
        return container.getId();
    }

    /**
     * Ensures the dedicated preview bridge network exists before a container is attached to it.
     * Called on every {@link #createAndStartContainer} so it's idempotent by construction: the
     * exists-check + create isn't atomic, so a concurrent call can race past it and get a 409
     * (Conflict) from Docker on create — that's caught and ignored since the network exists
     * either way by the time we observe it.
     */
    private void ensurePreviewNetwork() {
        // Docker's network list "name" filter matches by substring, not exact name — filtering
        // the returned candidates down to an exact name match avoids a superstring collision
        // (e.g. a leftover "qeploy-preview-old" network) being mistaken for the real one, which
        // would short-circuit real network creation and leave createAndStartContainer attaching
        // to a network name that was never actually created (review F1).
        List<Network> candidates = dockerClient.listNetworksCmd()
                .withNameFilter(PREVIEW_NETWORK_NAME)
                .exec();
        boolean exists = candidates.stream()
                .anyMatch(network -> PREVIEW_NETWORK_NAME.equals(network.getName()));
        if (exists) {
            verifyIccDisabled();
            return;
        }
        try {
            dockerClient.createNetworkCmd()
                    .withName(PREVIEW_NETWORK_NAME)
                    .withDriver("bridge")
                    // Disables inter-container communication on this bridge so one user's
                    // preview can't reach another's over the container network (lateral
                    // movement). Host reachability (gateway -> container, and container ->
                    // host services) is unaffected by this option — see design doc §2.
                    .withOptions(Map.of("com.docker.network.bridge.enable_icc", "false"))
                    .exec();
            log.info("Docker preview 네트워크 생성: name={}", PREVIEW_NETWORK_NAME);
        } catch (ConflictException e) {
            log.debug("Docker preview 네트워크가 동시 생성 레이스로 이미 존재함: name={}", PREVIEW_NETWORK_NAME);
        }
    }

    /**
     * An existing "qeploy-preview" network may predate this isolation policy (or have been
     * recreated manually) without the enable_icc=false option — that would silently disable the
     * inter-container isolation the whole policy exists for, with no other visible symptom.
     * We deliberately don't auto-fix/recreate it (a live network may already have containers
     * attached); a warn log is the operator-facing signal that isolation isn't actually in
     * effect (review F1). The list response doesn't reliably carry the full Options map, so
     * this re-fetches via inspect specifically to check it.
     */
    private void verifyIccDisabled() {
        Network network;
        try {
            network = dockerClient.inspectNetworkCmd().withNetworkId(PREVIEW_NETWORK_NAME).exec();
        } catch (Exception e) {
            log.warn("Docker preview 네트워크 격리 옵션 검증 실패(inspect 불가): name={} reason={}",
                    PREVIEW_NETWORK_NAME, e.getMessage());
            return;
        }
        Map<String, String> options = network.getOptions();
        String iccValue = options != null ? options.get("com.docker.network.bridge.enable_icc") : null;
        if (!"false".equals(iccValue)) {
            log.warn("Docker preview 네트워크의 격리 옵션(enable_icc=false)이 확인되지 않음 — "
                            + "컨테이너 간 통신이 차단되지 않을 수 있습니다: name={} actualIcc={}",
                    PREVIEW_NETWORK_NAME, iccValue);
        }
    }

    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Maps Docker's raw `inspect` state onto a serving-friendly snapshot for the status API.
     * A missing container is treated as "not running" rather than propagated (design doc D5) —
     * a preview session row can legitimately outlive its container (already removed by the
     * cleanup scheduler, or never started this run). Any other failure (daemon unreachable,
     * etc.) is left to propagate so it surfaces as a 500 instead of being disguised as 404.
     */
    public ContainerRuntimeStatus getContainerStatus(String containerId) {
        InspectContainerResponse inspect;
        try {
            inspect = dockerClient.inspectContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            return ContainerRuntimeStatus.notFound();
        }
        InspectContainerResponse.ContainerState state = inspect.getState();
        if (state == null) {
            return ContainerRuntimeStatus.notFound();
        }
        boolean running = Boolean.TRUE.equals(state.getRunning());
        // Docker keeps reporting the *previous* exit code (often a stale 0) while a container is
        // running — that's not a meaningful "it exited with 0" signal, so the contract (design
        // doc §1.1: "실행 중이거나 확인 불가 → null") requires forcing it to null while running,
        // regardless of what the raw inspect state says (review F3).
        return new ContainerRuntimeStatus(
                running,
                state.getOOMKilled(),
                running ? null : state.getExitCodeLong(),
                parseStartedAt(state.getStartedAt())
        );
    }

    private LocalDateTime parseStartedAt(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(startedAt), ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            // A cosmetic field is not worth a 500 — degrade to null per design doc §1.1.
            return null;
        }
    }

    /**
     * One-shot memory/CPU snapshot for a running container. Docker's `stats` endpoint needs at
     * least one CPU-usage sample plus a short settle window to compute a delta, so even a
     * successful call takes roughly a second (design doc §1.1) — that's why {@link #getStatus}
     * callers should treat this as expensive and only call it when the container is running.
     * Every failure mode here (container removed mid-call, timeout, missing fields) degrades to
     * {@link Optional#empty()} rather than failing the whole request, so the status endpoint can
     * still report running/oomKilled/exitCode with resources=null (design doc D5).
     */
    public Optional<ContainerResourceUsage> getContainerStats(String containerId) {
        StatsCallback callback = new StatsCallback();
        boolean completed;
        try {
            dockerClient.statsCmd(containerId).withNoStream(true).exec(callback);
            completed = callback.awaitCompletion(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!completed) {
            log.warn("Docker stats 조회 타임아웃({}s), resources=null로 degrade: containerId={}",
                    STATS_TIMEOUT_SECONDS, containerId);
            return Optional.empty();
        }
        Statistics stats = callback.getStatistics();
        if (stats == null || stats.getMemoryStats() == null) {
            return Optional.empty();
        }
        // memory usage/limit missing is itself a "can't determine resource usage" signal — the
        // design contract (§3: "memory: memoryStats.getUsage()/getLimit()") calls for degrading
        // the whole sample to empty rather than silently reporting a fabricated 0-byte reading,
        // which would look like a legitimately idle container instead of an unknown state
        // (review F7).
        MemoryStatsConfig memory = stats.getMemoryStats();
        if (memory.getUsage() == null || memory.getLimit() == null) {
            return Optional.empty();
        }
        return Optional.of(toResourceUsage(stats));
    }

    private ContainerResourceUsage toResourceUsage(Statistics stats) {
        MemoryStatsConfig memory = stats.getMemoryStats();
        double cpuPercent = computeCpuPercent(stats.getCpuStats(), stats.getPreCpuStats());
        return new ContainerResourceUsage(memory.getUsage(), memory.getLimit(), cpuPercent);
    }

    /**
     * cpuPercent = (cpuTotal - preCpuTotal) / (systemTotal - preSystemTotal) * onlineCpus * 100.
     * The very first stats sample after a container starts has an all-zero precpu_stats (no
     * prior sample to diff against), which would otherwise divide by zero or read as a negative
     * delta — both guarded here to return 0.0, matching `docker stats`' own first-sample
     * behavior instead of surfacing a bogus/negative percentage.
     */
    private double computeCpuPercent(CpuStatsConfig current, CpuStatsConfig previous) {
        if (current == null || previous == null) {
            return 0.0;
        }
        CpuUsageConfig currentUsage = current.getCpuUsage();
        CpuUsageConfig previousUsage = previous.getCpuUsage();
        if (currentUsage == null || previousUsage == null
                || currentUsage.getTotalUsage() == null || previousUsage.getTotalUsage() == null
                || current.getSystemCpuUsage() == null || previous.getSystemCpuUsage() == null) {
            return 0.0;
        }
        long cpuDelta = currentUsage.getTotalUsage() - previousUsage.getTotalUsage();
        long systemDelta = current.getSystemCpuUsage() - previous.getSystemCpuUsage();
        if (systemDelta <= 0 || cpuDelta < 0) {
            return 0.0;
        }
        long onlineCpus = current.getOnlineCpus() != null && current.getOnlineCpus() > 0
                ? current.getOnlineCpus()
                : 1L;
        return (double) cpuDelta / systemDelta * onlineCpus * 100;
    }

    /**
     * Adapter callback that keeps only the latest {@link Statistics} sample. `withNoStream(true)`
     * means Docker should emit exactly one sample before completing, but we defensively keep the
     * latest rather than the first in case that assumption ever changes upstream.
     */
    private static final class StatsCallback extends ResultCallback.Adapter<Statistics> {
        private volatile Statistics statistics;

        @Override
        public void onNext(Statistics object) {
            this.statistics = object;
        }

        Statistics getStatistics() {
            return statistics;
        }
    }

    /**
     * Fetches stdout+stderr for a container as a single timestamped text blob, mirroring the
     * Deployment `logText` contract (design doc D2) rather than returning structured line
     * objects. Logs are never persisted (D4) — Docker's json-file log driver is the source of
     * truth and disappears with the container. Callers must not re-log the returned text
     * server-side: it can contain secrets from the user's own application output.
     */
    public String getContainerLogs(String containerId, int tail, Integer sinceEpochSeconds) {
        LogContainerCmd command = dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTimestamps(true)
                .withFollowStream(false)
                .withTail(tail);
        if (sinceEpochSeconds != null) {
            command.withSince(sinceEpochSeconds);
        }

        LogCollectorCallback callback = new LogCollectorCallback();
        boolean completed;
        try {
            command.exec(callback);
            completed = callback.awaitCompletion(LOGS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (NotFoundException e) {
            // Container already removed — the session itself still exists, so this is a normal
            // "no logs available" 200 response, not a 404 (design doc §1.2).
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Docker 로그 조회 인터럽트: containerId=" + containerId, e);
        }
        String logText = callback.getLogText();
        if (!completed) {
            // Symmetric with getContainerStats' degrade-on-timeout style: rather than silently
            // returning a partial log as if it were complete, warn (server-side) and mark the
            // truncation in-band (client-facing) since logText has no separate "complete" flag
            // in the response contract (review F10).
            log.warn("Docker 로그 조회 타임아웃({}s), 절단된 로그 반환: containerId={} tail={}",
                    LOGS_TIMEOUT_SECONDS, containerId, tail);
            logText = logText + "\n[TRUNCATED] log fetch exceeded " + LOGS_TIMEOUT_SECONDS + "s timeout";
        }
        log.debug("Docker 컨테이너 로그 조회: containerId={} tail={}", containerId, tail);
        return logText;
    }

    /**
     * Accumulates raw frame bytes across the whole stream and decodes UTF-8 exactly once at the
     * end, instead of decoding each {@link Frame} independently — a single log line frequently
     * spans multiple frames, and a multi-byte UTF-8 character (e.g. Korean output) split across
     * a frame boundary would otherwise decode as U+FFFD replacement characters per-frame even
     * though the full byte sequence is valid (review F9).
     *
     * {@code onNext}/{@code getLogText} are both synchronized on {@code this}: the caller reads
     * {@link #getLogText()} right after {@code awaitCompletion} returns, which can be a timeout
     * (false) while the docker-java callback thread is still mid-write to {@code buffer} — the
     * synchronization isn't about serializing concurrent writers (Docker only opens one stream
     * per command), it's to guarantee the reading thread observes a consistent, fully-flushed
     * buffer instead of racing a concurrent write (review F10).
     */
    private static final class LogCollectorCallback extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void onNext(Frame frame) {
            try {
                buffer.write(frame.getPayload());
            } catch (IOException e) {
                // ByteArrayOutputStream never actually throws IOException; kept as a checked
                // catch only because OutputStream#write(byte[]) declares it.
            }
        }

        synchronized String getLogText() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    public List<com.github.dockerjava.api.model.Container> listAgentContainers() {
        Map<String, com.github.dockerjava.api.model.Container> containers = new LinkedHashMap<>();
        listContainersByLabel(AGENT_LABEL).forEach(container -> containers.put(container.getId(), container));
        listContainersByLabel(LEGACY_AGENT_LABEL).forEach(container -> containers.put(container.getId(), container));
        return List.copyOf(containers.values());
    }

    private List<com.github.dockerjava.api.model.Container> listContainersByLabel(String label) {
        return dockerClient.listContainersCmd()
                .withLabelFilter(List.of(label + "=true"))
                .exec();
    }

    public int getMappedPort(String containerId) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Ports.Binding[] bindings = inspect.getNetworkSettings() == null
                || inspect.getNetworkSettings().getPorts() == null
                || inspect.getNetworkSettings().getPorts().getBindings() == null
                ? null
                : inspect.getNetworkSettings().getPorts()
                .getBindings()
                .get(ExposedPort.tcp(CONTAINER_PORT));
        if (bindings == null
                || bindings.length == 0
                || bindings[0] == null
                || bindings[0].getHostPortSpec() == null
                || bindings[0].getHostPortSpec().isBlank()) {
            throw new IllegalStateException("컨테이너 포트 바인딩이 없습니다. containerId=" + containerId);
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    public String exec(String containerId, String command) {
        log.debug("Docker exec: {}", command);
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("sh", "-c", command)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            dockerClient.execStartCmd(execCreate.getId())
                    .withDetach(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDOUT) {
                                try { stdout.write(frame.getPayload()); } catch (Exception ignored) {}
                            } else if (frame.getStreamType() == StreamType.STDERR) {
                                try { stderr.write(frame.getPayload()); } catch (Exception ignored) {}
                            }
                        }
                    })
                    .awaitCompletion(EXEC_TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Docker exec 인터럽트: " + command, e);
        }

        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        return out + (err.isBlank() ? "" : "\n[STDERR]\n" + err);
    }

    /**
     * Restarts a running preview container in place (Cloud Ops Agent RESTART, EPIC 15 design §3.4)
     * — stop with a 5s grace period, then start, reusing the same container rather than recreating
     * it. This matters for the isolation policy in {@link #createAndStartContainer}: HostConfig
     * (memory/CPU/pids caps, capability drops, network isolation) is set at container-create time,
     * so a restart of the existing container preserves it automatically, whereas a
     * remove+recreate would need to reapply it explicitly.
     */
    public void restartContainer(String containerId) {
        try {
            dockerClient.restartContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            // Caller (InfraOpsAgentService) already resolved this containerId from an ACTIVE
            // preview session moments earlier — reaching here means the container was removed
            // out-of-band in that narrow window (cleanup scheduler, manual removal). Surfacing
            // this as a failure is correct (design §4.2 error table): unlike a missing session
            // (handled before this call is even made), a session that says ACTIVE but whose
            // container is gone is a genuine inconsistency worth failing the task over.
            throw new IllegalStateException("재시작할 컨테이너를 찾을 수 없습니다(이미 정리됨). containerId=" + containerId, e);
        }
        log.info("Docker 컨테이너 재시작: id={}", containerId);
    }

    public void removeContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            log.info("Docker 컨테이너가 이미 없습니다. stop 생략: id={}", containerId);
            return;
        } catch (NotModifiedException e) {
            log.info("Docker 컨테이너가 이미 중지되어 있습니다. remove 진행: id={}", containerId);
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (NotFoundException e) {
            log.info("Docker 컨테이너가 이미 없습니다. remove 생략: id={}", containerId);
            return;
        }
        log.info("Docker 컨테이너 제거: id={}", containerId);
    }

    private void pullImageIfNeeded() {
        try {
            dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(3, TimeUnit.MINUTES);
            log.info("Docker 이미지 준비 완료: {}", IMAGE);
        } catch (Exception e) {
            log.warn("이미지 pull 실패 (로컬에 존재할 수 있음): {}", e.getMessage());
        }
    }

    private void putLabel(Map<String, String> labels, String key, Object value) {
        if (value != null) {
            labels.put(key, String.valueOf(value));
        }
    }
}
