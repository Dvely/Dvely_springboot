package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Statistics;
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

    // --- BI-194 isolation policy: HostConfig applied to createContainerCmd -----------------

    @Test
    void createAndStartContainerAppliesIsolationHostConfig() {
        mockNetworkAlreadyExists();
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
        mockNetworkAlreadyExists();
        CreateContainerCmd createCommand = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCommand);
        when(createCommand.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("container-1");
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));

        service.createAndStartContainer(1L, "session-1", 11L, 21L, "task-1");

        verify(dockerClient, never()).createNetworkCmd();
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

    private void mockNetworkAlreadyExists() {
        ListNetworksCmd listCommand = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(dockerClient.listNetworksCmd()).thenReturn(listCommand);
        when(listCommand.exec()).thenReturn(List.of(mock(Network.class)));
    }

    // --- getContainerStatus -------------------------------------------------------------

    @Test
    void getContainerStatusMapsRunningContainerFields() {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(dockerClient.inspectContainerCmd("container-1")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        when(state.getOOMKilled()).thenReturn(false);
        when(state.getExitCodeLong()).thenReturn(null);
        when(state.getStartedAt()).thenReturn("2026-07-17T00:00:00.000000000Z");

        ContainerRuntimeStatus status = service.getContainerStatus("container-1");

        assertThat(status.running()).isTrue();
        assertThat(status.oomKilled()).isFalse();
        assertThat(status.exitCode()).isNull();
        assertThat(status.startedAt()).isNotNull();
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
