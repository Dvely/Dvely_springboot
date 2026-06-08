package com.example.dvely.agent.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerContainerServiceTest {

    @Mock private DockerClient dockerClient;

    private DockerContainerService service;

    @BeforeEach
    void setUp() {
        service = new DockerContainerService(dockerClient);
    }

    private InspectContainerResponse mockInspectWithBindings(Ports.Binding[] bindings) {
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        Ports ports = mock(Ports.class);

        when(dockerClient.inspectContainerCmd(anyString())).thenReturn(cmd);
        when(cmd.exec()).thenReturn(response);
        when(response.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getPorts()).thenReturn(ports);

        Map<ExposedPort, Ports.Binding[]> bindingsMap = new HashMap<>();
        bindingsMap.put(ExposedPort.tcp(3000), bindings);
        when(ports.getBindings()).thenReturn(bindingsMap);

        return response;
    }

    @Test
    void getMappedPort_바인딩_null이면_IllegalStateException() {
        mockInspectWithBindings(null);

        assertThatThrownBy(() -> service.getMappedPort("test-container"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("포트 바인딩 없음");
    }

    @Test
    void getMappedPort_바인딩_empty이면_IllegalStateException() {
        mockInspectWithBindings(new Ports.Binding[0]);

        assertThatThrownBy(() -> service.getMappedPort("test-container"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("포트 바인딩 없음");
    }

    @Test
    void getMappedPort_정상_바인딩이면_포트_반환() {
        mockInspectWithBindings(new Ports.Binding[]{Ports.Binding.bindPort(32768)});

        int port = service.getMappedPort("test-container");

        assertThat(port).isEqualTo(32768);
    }
}
