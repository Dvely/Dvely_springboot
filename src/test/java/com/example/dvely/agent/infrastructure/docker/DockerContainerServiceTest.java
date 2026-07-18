package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DockerClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DockerContainerServiceTest {

    @Mock
    private DockerClient dockerClient;

    private DockerContainerService service;

    @BeforeEach
    void setUp() {
        service = new DockerContainerService(dockerClient);
    }

    @Test
    void getMappedPortRejectsMissingBinding() {
        mockInspectWithBindings(null);

        assertThatThrownBy(() -> service.getMappedPort("container-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("포트 바인딩");
    }

    @Test
    void getMappedPortRejectsEmptyBinding() {
        mockInspectWithBindings(new Ports.Binding[0]);

        assertThatThrownBy(() -> service.getMappedPort("container-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("포트 바인딩");
    }

    @Test
    void getMappedPortReturnsHostPort() {
        mockInspectWithBindings(new Ports.Binding[]{Ports.Binding.bindPort(32768)});

        assertThat(service.getMappedPort("container-1")).isEqualTo(32768);
    }

    @Test
    void removeContainerIgnoresAlreadyRemovedContainer() {
        StopContainerCmd stopCommand = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("container-1")).thenReturn(stopCommand);
        when(stopCommand.withTimeout(5)).thenReturn(stopCommand);
        when(stopCommand.exec()).thenThrow(new NotFoundException("missing"));

        service.removeContainer("container-1");

        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    void removeContainerPropagatesUnexpectedRemoveFailure() {
        StopContainerCmd stopCommand = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCommand = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd("container-1")).thenReturn(stopCommand);
        when(stopCommand.withTimeout(5)).thenReturn(stopCommand);
        when(dockerClient.removeContainerCmd("container-1")).thenReturn(removeCommand);
        when(removeCommand.withForce(true)).thenReturn(removeCommand);
        when(removeCommand.exec()).thenThrow(new IllegalStateException("docker unavailable"));

        assertThatThrownBy(() -> service.removeContainer("container-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker unavailable");
    }

    // --- Cloud Ops Agent RESTART (EPIC 15 design §3.4) -------------------------------------

    @Test
    void restartContainerStopsWithGracePeriodThenStarts() {
        RestartContainerCmd restartCommand = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("container-1")).thenReturn(restartCommand);
        when(restartCommand.withTimeout(5)).thenReturn(restartCommand);

        service.restartContainer("container-1");

        verify(restartCommand).exec();
    }

    @Test
    void restartContainerWrapsNotFoundAsIllegalState() {
        RestartContainerCmd restartCommand = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("container-1")).thenReturn(restartCommand);
        when(restartCommand.withTimeout(5)).thenReturn(restartCommand);
        when(restartCommand.exec()).thenThrow(new NotFoundException("missing"));

        assertThatThrownBy(() -> service.restartContainer("container-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재시작할 컨테이너를 찾을 수 없습니다");
    }

    // --- BI-194 isolation policy: HostConfig applied to createContainerCmd -----------------

    @Test
    void createAndStartContainerAppliesIsolationHostConfig() {
        mockNetworkAlreadyExists(true);
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCommand).withHostConfig(hostConfigCaptor.capture());
        HostConfig hostConfig = hostConfigCaptor.getValue();
        assertThat(hostConfig.getMemory()).isEqualTo(1L << 30);
        assertThat(hostConfig.getMemorySwap()).isEqualTo(1L << 30);
        assertThat(hostConfig.getNanoCPUs()).isEqualTo(1_000_000_000L);
        assertThat(hostConfig.getPidsLimit()).isEqualTo(256L);
        assertThat(hostConfig.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(hostConfig.getCapAdd())
                .containsExactlyInAnyOrder(Capability.CHOWN, Capability.SETUID, Capability.SETGID);
        assertThat(hostConfig.getSecurityOpts()).containsExactly("no-new-privileges");
        assertThat(hostConfig.getNetworkMode()).isEqualTo("qeploy-preview");
    }

    @Test
    void createAndStartContainerSkipsNetworkCreationWhenNetworkAlreadyExists() {
        mockNetworkAlreadyExists(true);
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        verify(dockerClient, never()).createNetworkCmd();
    }

    // review F1: an exact-name match on the pre-existing network must still be verified for
    // enable_icc=false via inspect — a mismatch degrades to a warn log (not an exception, and
    // not an attempt to recreate a network that may already have live containers attached).
    @Test
    void createAndStartContainerWarnsButProceedsWhenExistingNetworkIccMismatched() {
        mockNetworkAlreadyExists(false);
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        assertThatCode(() -> service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1"))
                .doesNotThrowAnyException();

        verify(dockerClient).inspectNetworkCmd();
        verify(dockerClient, never()).createNetworkCmd();
    }

    // review F1: Docker's list "name" filter matches by substring — a leftover network whose
    // name merely *contains* "qeploy-preview" (but isn't an exact match) must not be mistaken
    // for the real network, or the real network never gets created.
    @Test
    void createAndStartContainerCreatesNetworkWhenOnlySuperstringMatchExists() {
        ListNetworksCmd listCommand = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(dockerClient.listNetworksCmd()).thenReturn(listCommand);
        Network superstringMatch = mock(Network.class);
        when(superstringMatch.getName()).thenReturn("qeploy-preview-old");
        when(listCommand.exec()).thenReturn(List.of(superstringMatch));
        CreateNetworkCmd createNetworkCommand = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCommand);
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        verify(dockerClient).createNetworkCmd();
        verify(dockerClient, never()).inspectNetworkCmd();
    }

    // review F5: the enable_icc=false option must actually reach createNetworkCmd, not just be
    // present in a code comment.
    @Test
    void createAndStartContainerCreatesNetworkWithIccDisabledOption() {
        ListNetworksCmd listCommand = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(dockerClient.listNetworksCmd()).thenReturn(listCommand);
        when(listCommand.exec()).thenReturn(List.of());
        CreateNetworkCmd createNetworkCommand = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCommand);
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(createNetworkCommand).withName("qeploy-preview");
        verify(createNetworkCommand).withDriver("bridge");
        verify(createNetworkCommand).withOptions(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).containsEntry("com.docker.network.bridge.enable_icc", "false");
    }

    @Test
    void createAndStartContainerIgnoresConcurrentNetworkCreateConflict() {
        ListNetworksCmd listCommand = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(dockerClient.listNetworksCmd()).thenReturn(listCommand);
        when(listCommand.exec()).thenReturn(List.of());
        CreateNetworkCmd createNetworkCommand = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCommand);
        when(createNetworkCommand.exec()).thenThrow(new ConflictException("network already exists"));
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        String containerId = service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        assertThat(containerId).isEqualTo("container-1");
        verify(dockerClient).startContainerCmd("container-1");
    }

    /**
     * @param iccDisabled whether the mocked inspect response reports the isolation option as
     *                    actually applied; the exact-match network is always found either way.
     */
    private void mockNetworkAlreadyExists(boolean iccDisabled) {
        ListNetworksCmd listCommand = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(dockerClient.listNetworksCmd()).thenReturn(listCommand);
        Network listed = mock(Network.class);
        when(listed.getName()).thenReturn("qeploy-preview");
        when(listCommand.exec()).thenReturn(List.of(listed));

        InspectNetworkCmd inspectCommand = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspectCommand);
        Network inspected = mock(Network.class);
        when(inspected.getOptions()).thenReturn(iccDisabled
                ? Map.of("com.docker.network.bridge.enable_icc", "false")
                : Map.of());
        when(inspectCommand.exec()).thenReturn(inspected);
    }

    // --- getContainerStatus -------------------------------------------------------------

    // review F3: Docker keeps reporting the *previous* exit code (often a stale 0) while a
    // container is running. Stubbing a non-null exitCode here while running=true is the actual
    // regression guard — asserting null with an unstubbed (already-null) exitCode wouldn't catch
    // a regression back to "pass the raw value through".
    @Test
    void getContainerStatusForcesExitCodeNullWhileRunning() {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(dockerClient.inspectContainerCmd("container-1")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        when(state.getOOMKilled()).thenReturn(false);
        // Ternary short-circuits and never actually calls getExitCodeLong() while running=true —
        // that's the point being proven, so this stub is deliberately lenient (defensive against
        // a future implementation that computes-then-discards instead of short-circuiting).
        lenient().when(state.getExitCodeLong()).thenReturn(137L);
        when(state.getStartedAt()).thenReturn("2026-07-17T00:00:00.000000000Z");

        ContainerRuntimeStatus status = service.getContainerStatus("container-1");

        assertThat(status.running()).isTrue();
        assertThat(status.oomKilled()).isFalse();
        assertThat(status.exitCode()).isNull();
        assertThat(status.startedAt()).isNotNull();
    }

    @Test
    void getContainerStatusPassesThroughExitCodeWhenNotRunning() {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(dockerClient.inspectContainerCmd("container-1")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        when(state.getOOMKilled()).thenReturn(false);
        when(state.getExitCodeLong()).thenReturn(1L);

        ContainerRuntimeStatus status = service.getContainerStatus("container-1");

        assertThat(status.running()).isFalse();
        assertThat(status.exitCode()).isEqualTo(1L);
    }

    @Test
    void getContainerStatusReturnsNotFoundWhenContainerMissing() {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("missing")).thenReturn(command);
        when(command.exec()).thenThrow(new NotFoundException("missing"));

        ContainerRuntimeStatus status = service.getContainerStatus("missing");

        assertThat(status).isEqualTo(ContainerRuntimeStatus.notFound());
    }

    // --- getContainerLogs ----------------------------------------------------------------

    @Test
    void getContainerLogsAppliesTailAndSinceThenDegradesToEmptyWhenContainerMissing() {
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        when(command.exec(any())).thenThrow(new NotFoundException("missing"));

        String logs = service.getContainerLogs("container-1", 500, 1_700_000_000);

        assertThat(logs).isEmpty();
        verify(command).withStdOut(true);
        verify(command).withStdErr(true);
        verify(command).withTimestamps(true);
        verify(command).withFollowStream(false);
        verify(command).withTail(500);
        verify(command).withSince(1_700_000_000);
    }

    @Test
    void getContainerLogsOmitsSinceWhenNotProvided() {
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        when(command.exec(any())).thenThrow(new NotFoundException("missing"));

        service.getContainerLogs("container-1", 200, null);

        verify(command, never()).withSince(any());
    }

    // review F9: a single multi-byte UTF-8 character (Korean) is split exactly across a frame
    // boundary here — decoding each frame independently (the pre-fix behavior) would show a
    // U+FFFD replacement character at the split point even though the full byte sequence, taken
    // together, is perfectly valid UTF-8.
    @Test
    void getContainerLogsAssemblesMultiByteUtf8CharacterSplitAcrossFrames() {
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        byte[] fullPayload = "안녕".getBytes(StandardCharsets.UTF_8); // 6 bytes: 3 + 3
        byte[] firstFrame = java.util.Arrays.copyOfRange(fullPayload, 0, 4); // splits 2nd char mid-sequence
        byte[] secondFrame = java.util.Arrays.copyOfRange(fullPayload, 4, fullPayload.length);
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(new Frame(StreamType.STDOUT, firstFrame));
            callback.onNext(new Frame(StreamType.STDOUT, secondFrame));
            callback.onComplete();
            return callback;
        });

        String logs = service.getContainerLogs("container-1", 200, null);

        assertThat(logs).isEqualTo("안녕");
        assertThat(logs).doesNotContain("�");
    }

    // review F10: on timeout the caller must be told the log is incomplete (truncation marker)
    // rather than silently returning a partial string as if it were the whole log; reading the
    // buffer after a timeout must also not race the callback thread still writing to it (the
    // synchronized onNext/getLogText pair in LogCollectorCallback is what makes this safe).
    @Test
    void getContainerLogsAppendsTruncationMarkerOnTimeout() {
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> callback = invocation.getArgument(0);
            // No onComplete(): simulate a daemon that never finishes the stream —
            // awaitCompletion(10s) genuinely blocks below and this test exercises that timeout.
            callback.onStart(() -> { });
            callback.onNext(new Frame(StreamType.STDOUT, "partial line".getBytes(StandardCharsets.UTF_8)));
            return callback;
        });

        String logs = service.getContainerLogs("container-1", 200, null);

        assertThat(logs).startsWith("partial line");
        assertThat(logs).contains("[TRUNCATED]");
    }

    // --- getContainerStats ----------------------------------------------------------------

    @Test
    void getContainerStatsReturnsZeroCpuPercentWhenSystemDeltaIsZero() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        Statistics stats = statisticsWithEqualCpuSamples();
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(stats);
            callback.onComplete();
            return callback;
        });

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isPresent();
        assertThat(usage.get().cpuPercent()).isEqualTo(0.0);
    }

    @Test
    void getContainerStatsReturnsEmptyWhenSamplingTimesOut() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            // Simulate a daemon that never responds: onStart only, no onNext/onComplete —
            // awaitCompletion(3s) genuinely blocks below and this test exercises that.
            callback.onStart(() -> { });
            return callback;
        });

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isEmpty();
    }

    @Test
    void getContainerStatsReturnsEmptyWhenContainerMissing() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        when(command.exec(any())).thenThrow(new NotFoundException("missing"));

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isEmpty();
    }

    // review F6: the only prior CPU% coverage exercised the guard path (equal cpu/precpu
    // samples, always 0.0) — this exercises the actual formula with distinct, realistic-looking
    // current/previous samples and asserts the exact expected percentage.
    @Test
    void getContainerStatsComputesCpuPercentFromDistinctCpuAndPrecpuSamples() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        Statistics stats = statisticsWithRealisticCpuSamples();
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(stats);
            callback.onComplete();
            return callback;
        });

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isPresent();
        // (1,300,000,000 - 1,100,000,000) / (20,000,000,000 - 19,000,000,000) * 2 onlineCpus * 100
        // = 0.2 * 2 * 100 = 40.0
        assertThat(usage.get().cpuPercent()).isEqualTo(40.0);
        assertThat(usage.get().memoryUsageBytes()).isEqualTo(268_435_456L);
        assertThat(usage.get().memoryLimitBytes()).isEqualTo(1_073_741_824L);
    }

    // review F7: null memory usage/limit must degrade the whole sample to empty (design §3),
    // not silently report a fabricated 0-byte reading.
    @Test
    void getContainerStatsReturnsEmptyWhenMemoryUsageIsNull() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        Statistics stats = mock(Statistics.class);
        MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
        when(memory.getUsage()).thenReturn(null);
        // getUsage()==null short-circuits the `||` check before getLimit() is ever called —
        // deliberately lenient (defensive against a future implementation that evaluates both).
        lenient().when(memory.getLimit()).thenReturn(1_073_741_824L);
        when(stats.getMemoryStats()).thenReturn(memory);
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(stats);
            callback.onComplete();
            return callback;
        });

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isEmpty();
    }

    @Test
    void getContainerStatsReturnsEmptyWhenMemoryLimitIsNull() {
        StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
        when(dockerClient.statsCmd("container-1")).thenReturn(command);
        Statistics stats = mock(Statistics.class);
        MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
        when(memory.getUsage()).thenReturn(268_435_456L);
        when(memory.getLimit()).thenReturn(null);
        when(stats.getMemoryStats()).thenReturn(memory);
        when(command.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(stats);
            callback.onComplete();
            return callback;
        });

        Optional<ContainerResourceUsage> usage = service.getContainerStats("container-1");

        assertThat(usage).isEmpty();
    }

    // review F4 (High): locks docker-java's lower version bound. FAILS on 3.3.6 (Capability has
    // no custom deserializer; Enum.valueOf("CAP_CHOWN") throws InvalidFormatException) and
    // PASSES on 3.7.1+ (Capability.fromValue() is a @JsonCreator that strips the "CAP_" prefix
    // before Enum.valueOf). Root cause: modern Docker Engine (confirmed against 29.6.1 / API
    // 1.55, and independently via a raw REST call bypassing docker-java entirely) returns
    // capability names prefixed with "CAP_" in inspect responses once HostConfig.CapAdd/CapDrop
    // is set — which this service's isolation policy always does — so a regression back to
    // 3.3.6 would silently break inspectContainerCmd().exec() (and therefore getMappedPort,
    // getContainerStatus, and preview session acquisition itself) for every real container,
    // invisibly to every mock-based test in this file.
    @Test
    void dockerJavaObjectMapperDeserializesCapPrefixedCapability_lowerBoundRegressionGuard() throws Exception {
        ObjectMapper dockerJavaObjectMapper = DockerClientConfig.getDefaultObjectMapper();

        Capability capability = dockerJavaObjectMapper.readValue("\"CAP_CHOWN\"", Capability.class);

        assertThat(capability).isEqualTo(Capability.CHOWN);
    }

    private Statistics statisticsWithRealisticCpuSamples() {
        Statistics stats = mock(Statistics.class);
        MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
        when(memory.getUsage()).thenReturn(268_435_456L); // 256 MiB
        when(memory.getLimit()).thenReturn(1_073_741_824L); // 1 GiB
        when(stats.getMemoryStats()).thenReturn(memory);

        CpuUsageConfig currentUsage = mock(CpuUsageConfig.class);
        when(currentUsage.getTotalUsage()).thenReturn(1_300_000_000L);
        CpuStatsConfig current = mock(CpuStatsConfig.class);
        when(current.getCpuUsage()).thenReturn(currentUsage);
        when(current.getSystemCpuUsage()).thenReturn(20_000_000_000L);
        when(current.getOnlineCpus()).thenReturn(2L);

        CpuUsageConfig previousUsage = mock(CpuUsageConfig.class);
        when(previousUsage.getTotalUsage()).thenReturn(1_100_000_000L);
        CpuStatsConfig previous = mock(CpuStatsConfig.class);
        when(previous.getCpuUsage()).thenReturn(previousUsage);
        when(previous.getSystemCpuUsage()).thenReturn(19_000_000_000L);

        when(stats.getCpuStats()).thenReturn(current);
        when(stats.getPreCpuStats()).thenReturn(previous);
        return stats;
    }

    private Statistics statisticsWithEqualCpuSamples() {
        Statistics stats = mock(Statistics.class);
        MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
        when(memory.getUsage()).thenReturn(100L);
        when(memory.getLimit()).thenReturn(1_073_741_824L);
        when(stats.getMemoryStats()).thenReturn(memory);

        CpuUsageConfig usage = mock(CpuUsageConfig.class);
        when(usage.getTotalUsage()).thenReturn(1_000L);
        CpuStatsConfig cpuStats = mock(CpuStatsConfig.class);
        when(cpuStats.getCpuUsage()).thenReturn(usage);
        when(cpuStats.getSystemCpuUsage()).thenReturn(50_000L);
        // Same totals on both samples => systemDelta == 0 => first-sample guard short-circuits
        // before onlineCpus is even read, so it's intentionally left unstubbed (null -> guarded).
        when(stats.getCpuStats()).thenReturn(cpuStats);
        when(stats.getPreCpuStats()).thenReturn(cpuStats);
        return stats;
    }

    private void mockInspectWithBindings(Ports.Binding[] bindings) {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        Ports ports = mock(Ports.class);

        when(dockerClient.inspectContainerCmd(anyString())).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getPorts()).thenReturn(ports);

        Map<ExposedPort, Ports.Binding[]> bindingMap = new HashMap<>();
        bindingMap.put(ExposedPort.tcp(3000), bindings);
        when(ports.getBindings()).thenReturn(bindingMap);
    }
}
