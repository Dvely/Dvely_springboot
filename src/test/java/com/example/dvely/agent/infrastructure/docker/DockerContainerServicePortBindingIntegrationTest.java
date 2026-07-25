package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #76 (BI-081/G1) — real-Docker regression guard for the preview container's host port bind
 * address. `DockerContainerServiceTest`'s mock-based coverage can only prove which arguments this
 * service *sends* to docker-java; it cannot prove what the daemon actually does with them (the
 * pre-fix bug — HostIp resolving to 0.0.0.0/every-interface — lived entirely on the daemon side of
 * that boundary, per the audit's F3/F4: the code called `bindPort(0)`, a call that *looks*
 * innocuous, and the daemon's default behavior is what turned it into an external exposure). This
 * class creates real containers against the local Docker daemon and inspects the daemon's own
 * report of what it bound, mirroring how `DockerContainerServiceTest` already locks the docker-java
 * version floor with a real `ObjectMapper` deserialization rather than a mock.
 *
 * <p>Requires a reachable Docker daemon and the {@code node:20-alpine} image (pulled on demand);
 * both are assumed present the same way they're assumed present for every other preview-container
 * code path in this service — there is no separate "docker unavailable" skip here because a preview
 * session cannot function without one either.
 */
class DockerContainerServicePortBindingIntegrationTest {

    private static final ExposedPort CONTAINER_PORT_3000 = ExposedPort.tcp(3000);

    private DockerClient dockerClient;
    private DockerContainerService service;
    private String containerId;

    @BeforeEach
    void setUp() {
        // Mirrors DockerContainerService()'s own no-arg bootstrap (same unix socket / npipe
        // target). Duplicated here — rather than reusing the service's internal client — because
        // the production constructor never exposes the DockerClient it builds, and this test
        // needs direct `inspect` access to NetworkSettings.Ports[...].HostIp: a field
        // DockerContainerService's own public API deliberately never surfaces (see
        // getMappedPort's Javadoc on why it only reads the port, not the IP).
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
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
        service = new DockerContainerService(dockerClient);
    }

    @AfterEach
    void tearDown() {
        // Always remove the container this test created, pass or fail — a leaked preview
        // container would otherwise keep holding the BI-194 isolation policy's real host
        // resources (1 GiB memory, 1 vCPU) indefinitely, since nothing else owns its lifecycle.
        if (containerId != null) {
            service.removeContainer(containerId);
        }
    }

    @Test
    void createAndStartContainerPublishesHostPortOnLoopbackOnly() {
        containerId = service.createAndStartContainer(
                999_000L, "it-session-" + System.nanoTime(), 1L, 1L, "it-task-" + System.nanoTime());

        Ports.Binding[] bindings = inspectPortBindings(containerId);

        // The assertion this whole test exists for (audit F3/F4): HostIp must be the loopback
        // address, never left unset (Docker resolves an unset HostIp to 0.0.0.0 — every
        // interface) and never "::" (the IPv6 wildcard). Before this fix, a real daemon reported
        // *two* binding entries here (0.0.0.0 and ::, confirmed manually against this same image);
        // the loopback bind narrows a real daemon's response to exactly one.
        assertThat(bindings).hasSize(1);
        assertThat(bindings[0].getHostIp()).isEqualTo("127.0.0.1");
        assertThat(bindings[0].getHostIp()).isNotEqualTo("0.0.0.0");
        assertThat(bindings[0].getHostIp()).isNotEqualTo("::");

        // getMappedPort() must keep parsing correctly against this narrowed (single-entry)
        // structure — the audit called this out (F6) as a point to verify, not assume, once the
        // binding shape changes from two entries down to one.
        int mappedPort = service.getMappedPort(containerId);
        assertThat(mappedPort).isEqualTo(Integer.parseInt(bindings[0].getHostPortSpec()));
        assertThat(mappedPort).isPositive();
    }

    // Issue #71/#76: restartContainer reallocates the dynamic host port (audit F5) — this proves
    // the loopback bind survives that reallocation too, not just the initial create. The exact
    // port number is allowed to change across restart (that's the reallocation itself, and #71's
    // stale-hostPort-column problem is a separate, already-handled concern); what must NOT change
    // is HostIp.
    @Test
    void restartContainerKeepsHostPortOnLoopbackAfterReallocation() {
        containerId = service.createAndStartContainer(
                999_001L, "it-session-restart-" + System.nanoTime(), 1L, 1L,
                "it-task-restart-" + System.nanoTime());

        service.restartContainer(containerId);

        Ports.Binding[] bindings = inspectPortBindings(containerId);
        assertThat(bindings).hasSize(1);
        assertThat(bindings[0].getHostIp()).isEqualTo("127.0.0.1");

        int mappedPort = service.getMappedPort(containerId);
        assertThat(mappedPort).isEqualTo(Integer.parseInt(bindings[0].getHostPortSpec()));
        assertThat(mappedPort).isPositive();
    }

    private Ports.Binding[] inspectPortBindings(String containerId) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return inspect.getNetworkSettings().getPorts().getBindings().get(CONTAINER_PORT_3000);
    }
}
