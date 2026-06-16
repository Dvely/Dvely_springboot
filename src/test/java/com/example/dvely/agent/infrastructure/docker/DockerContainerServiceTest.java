package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
