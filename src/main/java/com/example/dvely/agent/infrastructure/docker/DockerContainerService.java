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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
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
    // Host-side bind address for the preview container's published port (Issue #76, BI-081/G1).
    // Kept as a plain constant rather than a configuration property, matching the
    // IMAGE/CONTAINER_PORT style above and the BI-194 isolation constants further below: a real
    // need to tune it hasn't appeared. Loopback-only is a deliberate security boundary, not an
    // incidental default — see the comment at the bind call below.
    private static final String HOST_BIND_IP = "127.0.0.1";
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
    public static final long MEMORY_LIMIT_BYTES = 1L << 30; // 1 GiB: dev server + npm install headroom
    // JAVA_FULLSTACK 은 JVM + gradle 빌드가 무거워 1 GiB 로는 OOM 위험이 크다. 그래서 저장된
    // runtimeType 이 JAVA_FULLSTACK 인 프로젝트는 이 값으로 컨테이너를 만든다(생성 시점에 정해지므로
    // 사용자가 설정에서 미리 골라야 이 큰 컨테이너를 받는다 — 자동 감지는 클론 후라 늦다).
    public static final long JAVA_MEMORY_LIMIT_BYTES = 2L << 30; // 2 GiB
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
        return createAndStartContainer(userId, previewSessionId, projectId,
                conversationId, taskId, MEMORY_LIMIT_BYTES);
    }

    /**
     * 메모리 상한을 지정해 프리뷰 컨테이너를 만든다. JAVA_FULLSTACK 은 JVM+gradle 때문에
     * {@link #JAVA_MEMORY_LIMIT_BYTES} 를 넘긴다. swap 은 메모리와 같게 둬(추가 swap 없음) OOM 이
     * 느린 디스크 뒤로 숨지 않고 깨끗하게 kill 되도록 한다.
     */
    public String createAndStartContainer(Long userId,
                                          String previewSessionId,
                                          Long projectId,
                                          Long conversationId,
                                          String taskId,
                                          long memoryBytes) {
        pullImageIfNeeded();
        ensurePreviewNetwork();

        ExposedPort exposedPort = ExposedPort.tcp(CONTAINER_PORT);
        Ports portBindings = new Ports();
        // Bind to loopback only, with a dynamic (0 = daemon-assigned) host port (Issue #76,
        // BI-081/G1 — see audit .agent-team/01-reverse/preview-exposure-audit.md §2.1 F1-F4).
        // `Ports.Binding.bindPort(int)` leaves HostIp unset, which Docker resolves to 0.0.0.0 —
        // i.e. every network interface, reachable from outside the host. The container behind
        // this port runs an unauthenticated static file server (`npx serve`, no session/token
        // check of its own), so an unset HostIp was a full bypass of the gateway's accessToken
        // check (PreviewGatewayService) and Spring Security entirely. `PreviewGatewayService`
        // already only ever proxies to `127.0.0.1:hostPort`, so it never needed the port reachable
        // from any other interface — this binding just stops promising more than that.
        // PRD §14.2's "internal-network-only access" principle is what this enforces at the
        // Docker layer instead of leaving it to host-firewall configuration (which the repo has
        // no way to guarantee, per the audit's G5).
        // NOTE for future readers: this hard-codes "the gateway and the Docker daemon are on the
        // same host". If preview containers ever move to a remote/multi-host Docker daemon, this
        // loopback bind must be revisited together with the gateway's proxy target — otherwise
        // the gateway simply can't reach the container at all.
        portBindings.bind(exposedPort, Ports.Binding.bindIpAndPort(HOST_BIND_IP, 0));

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
                        .withMemory(memoryBytes)
                        .withMemorySwap(memoryBytes)
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

    /**
     * 데몬에 실제로 닿는지 한 번 확인한다.
     *
     * <p>이 클래스는 첫 컨테이너 요청 전까지 데몬에 연결하지 않으므로(생성자는 클라이언트 객체만
     * 만든다), Docker 가 없는 서버에서도 앱은 아무 경고 없이 기동한다 — 그리고 사용자가 프리뷰를
     * 누르는 순간에야 처음 실패한다({@code deploy/README.md} §4). 기동 직후 이 핑 한 번으로 그
     * 침묵을 없앤다.</p>
     */
    public boolean ping() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception exception) {
            log.debug("Docker ping 실패", exception);
            return false;
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

    /**
     * Reads back the daemon-assigned host port for {@link #CONTAINER_PORT} (used after both
     * initial start and {@link #restartContainer}, which reallocates it — Issue #71/#76). Only
     * the port is parsed, not {@code HostIp}: before the loopback fix this array could hold two
     * entries (0.0.0.0 and ::, since an unset HostIp binds every interface); with the loopback
     * bind in {@link #createAndStartContainer} it holds exactly one ({@code 127.0.0.1}). Either
     * way {@code bindings[0]} is the port we need, so this method is intentionally unaware of
     * which host address produced it — that's `createAndStartContainer`'s decision, not this
     * getter's (verified against a real container in
     * DockerContainerServicePortBindingIntegrationTest).
     */
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
        return execWithExitCode(containerId, command).output();
    }

    /**
     * 종료 코드까지 함께 돌려주는 exec.
     *
     * {@link #exec} 는 오랫동안 stdout·stderr 만 모으고 종료 코드를 읽지 않았다. 그래서 컨테이너
     * 안에서 명령이 실패해도 호출자에게는 문자열만 돌아갔고, 실패가 조용히 성공으로 넘어갔다.
     * 프리뷰 빌드가 그 경로로 무력화됐다 — {@code set -o pipefail} 을 걸어둬도 종료 코드를
     * 아무도 안 보니 의미가 없었고, 빌드가 깨져도 이전 산출물이 그대로 서빙됐다.
     *
     * 그렇다고 {@link #exec} 를 실패 시 던지도록 바꾸지는 않는다. 호출부가 48곳인데 상당수는
     * 실패해도 되는 명령이다 — 없는 파일을 {@code cat} 하거나, 이미 있는 remote 를
     * {@code git remote add} 하거나, {@code [ -d ... ] && echo yes || echo no} 로 존재를
     * 물어보는 식이다. 그런 곳까지 예외로 바꾸면 멀쩡히 돌던 흐름이 깨진다.
     *
     * 그래서 실패가 반드시 전달돼야 하는 곳만 이 메서드를 쓴다.
     */
    public ExecResult execWithExitCode(String containerId, String command) {
        return execWithExitCode(containerId, command, List.of());
    }

    /**
     * 환경변수를 함께 넘기는 exec. env 는 {@code KEY=VALUE} 리스트로 exec 프로세스에 주입된다.
     *
     * env 를 명령 문자열에 넣지 않고 {@link com.github.dockerjava.api.command.ExecCreateCmd#withEnv}
     * 로만 전달하는 것이 핵심이다 — 위 {@code log.debug("Docker exec: {}", command)} 와 인터럽트
     * 예외 메시지에는 command 만 남으므로, DB 비밀번호 같은 값이 로그·예외로 새지 않는다.
     * (프리뷰 백엔드 런타임에 사용자 env + DB 커넥션을 주입하는 경로가 이걸 쓴다.)
     */
    public ExecResult execWithExitCode(String containerId, String command, List<String> env) {
        log.debug("Docker exec: {}", command);
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withEnv(env == null || env.isEmpty() ? null : List.copyOf(env))
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
        String output = out + (err.isBlank() ? "" : "\n[STDERR]\n" + err);

        // 종료 코드는 exec 이 끝난 뒤에야 확정된다. 아직 running 이면 null 이 오는데, 위에서
        // awaitCompletion 을 지나온 뒤라 정상 경로에서는 값이 있다.
        Long exitCode = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
        return new ExecResult(exitCode == null ? -1 : exitCode.intValue(), output);
    }

    /** exec 결과. exitCode 가 -1 이면 Docker 가 종료 코드를 확정하지 못한 경우다. */
    public record ExecResult(int exitCode, String output) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    // ── LOCAL DB 프로비저닝 (세션 전용 네트워크에 DB 컨테이너를 형제로 띄운다) ──────────
    //
    // 왜 프리뷰 컨테이너 안이 아니라 형제 컨테이너인가: 프리뷰 컨테이너는 cap-drop ALL 이라
    // 그 안에서 docker 를 못 돌린다(DinD 불가). 왜 preview 네트워크가 아니라 세션 전용
    // 네트워크인가: preview 네트워크는 ICC 를 꺼서(enable_icc=false) 앱과 DB 가 서로 못 본다.
    // 그래서 세션마다 ICC 가 켜진 격리 네트워크를 따로 만들고 그 안에 앱+DB 만 넣는다 —
    // 세션 간 격리는 유지되고(다른 네트워크는 이 DB 이름을 해석조차 못 함, 2026-09-01 실측),
    // 세션 안에서만 통신이 허용된다.

    private static final String PROVISION_NETWORK_PREFIX = "qeploy-db-";
    private static final long DB_MEMORY_LIMIT_BYTES = 512L << 20; // 512 MiB
    private static final int DB_READY_TIMEOUT_SECONDS = 60;

    /** 세션 전용 네트워크를 만든다(ICC 켜짐 — 세션 안 앱↔DB 통신 허용). 이름으로 세션을 식별한다. */
    public String createSessionNetwork(String sessionId) {
        String name = PROVISION_NETWORK_PREFIX + sessionId;
        try {
            dockerClient.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    // preview 네트워크와 달리 ICC 를 끄지 않는다. 이 네트워크에는 한 세션의 앱과
                    // DB 만 들어오므로, 그 둘 사이 통신을 막으면 DB 접속 자체가 안 된다. 세션 간
                    // 격리는 "네트워크가 세션마다 다름"으로 이미 보장된다.
                    .withLabels(Map.of(AGENT_LABEL, "true", "qeploy.dbSession", sessionId))
                    .exec();
            log.info("DB 세션 네트워크 생성: name={}", name);
        } catch (ConflictException e) {
            log.debug("DB 세션 네트워크가 이미 존재함: name={}", name);
        }
        return name;
    }

    /**
     * DB 컨테이너를 세션 네트워크에 형제로 띄우고, 준비될 때까지 기다린 뒤 컨테이너 ID 를 돌려준다.
     *
     * networkAlias 로 앱이 접속한다(예: "db"). 준비 확인은 엔진별 핑으로 실제로 연결을 받을 수
     * 있을 때까지 폴링한다 — "컨테이너가 떴다"와 "접속을 받는다"는 다르고, 후자가 돼야 READY 다.
     *
     * @throws IllegalStateException 제한 시간 안에 준비되지 않으면. 호출자가 세션을 FAILED 로 닫는다.
     */
    public String createDatabaseContainer(String networkName, String networkAlias, String image,
                                          List<String> env, List<String> readyProbe) {
        pullImageIfNeeded(image);
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withEnv(env)
                .withHostConfig(HostConfig.newHostConfig()
                        .withMemory(DB_MEMORY_LIMIT_BYTES)
                        .withMemorySwap(DB_MEMORY_LIMIT_BYTES)
                        .withNanoCPUs(NANO_CPUS)
                        .withPidsLimit(PIDS_LIMIT)
                        // DB 엔진은 초기화 때 파일 소유권을 바꿔 권한을 낮춘다. postgres·mysql 이
                        // 실측에서 이 cap 세트로 정상 기동했다(2026-09-01).
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID,
                                Capability.DAC_OVERRIDE, Capability.FOWNER, Capability.SETFCAP)
                        .withSecurityOpts(List.of("no-new-privileges"))
                        .withNetworkMode(networkName))
                .withAliases(networkAlias)
                .withLabels(Map.of(AGENT_LABEL, "true"))
                .exec();
        dockerClient.startContainerCmd(container.getId()).exec();
        String id = container.getId();
        log.info("DB 컨테이너 시작: id={} alias={} image={}", id, networkAlias, image);

        for (int i = 0; i < DB_READY_TIMEOUT_SECONDS; i++) {
            ExecResult probe = execWithExitCode(id, String.join(" ", readyProbe));
            if (probe.succeeded()) {
                log.info("DB 컨테이너 준비 완료: id={} ({}초)", id, i + 1);
                return id;
            }
            sleepSeconds(1);
        }
        // 준비 안 됐으면 방금 만든 컨테이너를 남기지 않는다.
        removeDatabaseContainer(id);
        throw new IllegalStateException("DB 컨테이너가 " + DB_READY_TIMEOUT_SECONDS + "초 안에 준비되지 않았습니다.");
    }

    /**
     * 이미 떠 있는 컨테이너(프리뷰 앱)를 세션 전용 네트워크에 추가로 연결한다. 이래야 앱이 그
     * 네트워크의 DB 별칭("db")을 DNS 로 풀 수 있다 — DB 만 네트워크에 넣고 앱을 안 붙이면
     * READY 로 떠도 앱은 접속하지 못한다.
     */
    public void connectContainerToNetwork(String networkName, String containerId) {
        try {
            dockerClient.connectToNetworkCmd()
                    .withNetworkId(networkName)
                    .withContainerId(containerId)
                    .exec();
            log.info("컨테이너를 세션 네트워크에 연결: container={} network={}", containerId, networkName);
        } catch (NotModifiedException e) {
            log.debug("컨테이너가 이미 네트워크에 연결됨: container={} network={}", containerId, networkName);
        }
    }

    /** DB 컨테이너를 강제 제거한다. 이미 없으면 조용히 넘어간다. */
    public void removeDatabaseContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.info("DB 컨테이너 제거: id={}", containerId);
        } catch (NotFoundException e) {
            log.debug("DB 컨테이너가 이미 없음: id={}", containerId);
        }
    }

    /**
     * DB 컨테이너와 그것이 붙어 있던 세션 전용 네트워크를 함께 정리한다. resourceId(컨테이너 ID)
     * 하나만으로 완전 회수가 되도록, 컨테이너를 지우기 전에 붙은 qeploy-db-* 네트워크를 역추적한다.
     * 워커가 이 메서드로 만료된 LOCAL DB 를 통째로 정리한다.
     */
    public void removeDatabaseContainerWithNetwork(String containerId) {
        String sessionNetwork = null;
        try {
            var networks = dockerClient.inspectContainerCmd(containerId).exec()
                    .getNetworkSettings().getNetworks();
            if (networks != null) {
                sessionNetwork = networks.keySet().stream()
                        .filter(n -> n.startsWith(PROVISION_NETWORK_PREFIX))
                        .findFirst().orElse(null);
            }
        } catch (NotFoundException e) {
            log.debug("정리 대상 DB 컨테이너가 이미 없음: id={}", containerId);
        }
        removeDatabaseContainer(containerId);
        if (sessionNetwork != null) {
            removeSessionNetwork(sessionNetwork);
        }
    }

    /**
     * 세션 네트워크를 제거한다. 아직 붙어 있는 컨테이너(예: 만료 시점에도 살아 있는 프리뷰 앱)를
     * 먼저 강제 분리해야 removeNetwork 가 "network has active endpoints" 로 실패하지 않는다.
     */
    public void removeSessionNetwork(String networkName) {
        try {
            var attached = dockerClient.inspectNetworkCmd().withNetworkId(networkName).exec().getContainers();
            if (attached != null) {
                for (String cid : attached.keySet()) {
                    try {
                        dockerClient.disconnectFromNetworkCmd()
                                .withNetworkId(networkName).withContainerId(cid).withForce(true).exec();
                    } catch (RuntimeException e) {
                        log.debug("네트워크 분리 무시(이미 없음): container={} network={}", cid, networkName);
                    }
                }
            }
            dockerClient.removeNetworkCmd(networkName).exec();
            log.info("DB 세션 네트워크 제거: name={}", networkName);
        } catch (NotFoundException e) {
            log.debug("DB 세션 네트워크가 이미 없음: name={}", networkName);
        }
    }

    private void sleepSeconds(int sec) {
        try {
            Thread.sleep(sec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DB 준비 대기 인터럽트", e);
        }
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

    /**
     * 컨테이너 안의 파일 하나를 호스트로 꺼낸다. Docker 의 copy 는 tar 스트림을 주므로, 그 안의 첫
     * 정규 파일 엔트리를 destFile 로 푼다(빌드 산출물 jar 를 EC2 로 넘기기 전에 컨트롤 플레인으로
     * 추출하는 용도 — 빌드 컨테이너에는 클라우드 자격을 주지 않기 위함이다).
     */
    public void copyFileFromContainer(String containerId, String containerPath, Path destFile) {
        try (InputStream tar = dockerClient.copyArchiveFromContainerCmd(containerId, containerPath).exec();
             TarArchiveInputStream tin = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry;
            while ((entry = tin.getNextEntry()) != null) {
                if (entry.isFile()) {
                    try (OutputStream out = Files.newOutputStream(destFile)) {
                        tin.transferTo(out);
                    }
                    return;
                }
            }
            throw new IllegalStateException(
                    "컨테이너에서 파일을 찾지 못했습니다: " + containerId + ":" + containerPath);
        } catch (IOException e) {
            throw new RuntimeException("컨테이너 파일 추출 실패: " + containerPath, e);
        }
    }

    /**
     * 호스트 Docker 데몬에서 이미지를 빌드한다. contextTarFile 은 빌드 컨텍스트(루트에 Dockerfile)를
     * 담은 tar. 스택 무관 배포(DOCKER 모드)의 핵심 — 앱의 Dockerfile 로 무엇이든 같은 경로로 빌드한다.
     *
     * <p><b>보안:</b> 신뢰할 수 없는 사용자 Dockerfile 이 호스트 데몬에서 빌드된다(빌드 스텝은 데몬의
     * 빌드 컨테이너). 단일 테넌트·검증 단계에선 gradle/npm 을 샌드박스에서 돌리는 것과 유사한 트러스트다.
     * 멀티테넌트 운영 전엔 kaniko/rootless 로 하드닝해야 한다(설계 문서 참고).</p>
     *
     * @return 빌드된 이미지 ID
     */
    public String buildImage(Path contextTarFile, String imageTag) {
        try (InputStream tar = Files.newInputStream(contextTarFile)) {
            return dockerClient.buildImageCmd()
                    .withTarInputStream(tar)
                    .withTags(java.util.Set.of(imageTag))
                    .exec(new com.github.dockerjava.api.command.BuildImageResultCallback())
                    .awaitImageId();
        } catch (IOException e) {
            throw new RuntimeException("이미지 빌드 컨텍스트 읽기 실패: " + contextTarFile, e);
        }
    }

    /** 이미지를 호스트 임시 tar 로 저장해 그 경로를 돌려준다(S3 로 넘기기 전 추출). */
    public Path saveImage(String imageTag) {
        try {
            Path dest = Files.createTempFile("qeploy-image-", ".tar");
            try (InputStream img = dockerClient.saveImageCmd(imageTag).exec();
                 OutputStream out = Files.newOutputStream(dest)) {
                img.transferTo(out);
            }
            return dest;
        } catch (IOException e) {
            throw new RuntimeException("이미지 save 실패: " + imageTag, e);
        }
    }

    /** 로컬 이미지 삭제(save 후 컨트롤 플레인 디스크 정리). 없으면 무시. */
    public void removeImage(String imageTag) {
        try {
            dockerClient.removeImageCmd(imageTag).withForce(true).exec();
        } catch (NotFoundException e) {
            log.debug("이미지가 이미 없음: {}", imageTag);
        } catch (RuntimeException e) {
            log.warn("이미지 삭제 실패(무시): {} 원인={}", imageTag, e.toString());
        }
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
        pullImageIfNeeded(IMAGE);
    }

    private void pullImageIfNeeded(String image) {
        try {
            dockerClient.pullImageCmd(image).start().awaitCompletion(3, TimeUnit.MINUTES);
            log.info("Docker 이미지 준비 완료: {}", image);
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
