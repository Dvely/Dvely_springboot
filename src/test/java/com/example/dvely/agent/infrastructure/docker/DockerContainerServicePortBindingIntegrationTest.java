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
 * Issue #76 (BI-081/G1) 의 후신 — 실제 데몬을 상대로 프리뷰 컨테이너의 <b>노출면</b>을 지킨다.
 *
 * <p>원래는 발행된 호스트 포트가 루프백에만 묶이는지를 봤다. #358 에서 발행 자체를 없앴으므로
 * 지켜야 할 성질이 더 강해졌다: <b>호스트 포트가 하나도 없어야 하고</b>, 게이트웨이는 컨테이너
 * IP 로만 닿는다.</p>
 *
 * <p>목 기반 테스트는 이 서비스가 docker-java 에 <i>무엇을 보내는지</i>까지만 증명한다. 원래 버그
 * (HostIp 가 0.0.0.0 으로 해석되던 것)는 데몬 쪽에서 일어났고, 그 경계 너머는 실제 데몬만 말해준다.
 * 그래서 여기서는 데몬이 <i>실제로 무엇을 했는지</i>를 inspect 로 확인한다.</p>
 *
 * <p>Docker 데몬과 {@code node:20-alpine} 이 필요하다 — 프리뷰 경로 전체가 그것을 전제하므로
 * 별도 skip 을 두지 않는다.</p>
 */
class DockerContainerServicePortBindingIntegrationTest {

    private static final ExposedPort CONTAINER_PORT_3000 = ExposedPort.tcp(3000);

    private DockerClient dockerClient;
    private DockerContainerService service;
    private String containerId;

    @BeforeEach
    void setUp() {
        // DockerContainerService() 의 무인자 부트스트랩과 같은 대상(unix socket / npipe)이다.
        // 서비스 내부 클라이언트를 재사용하지 않고 여기서 따로 만드는 이유는, 운영 생성자가
        // DockerClient 를 드러내지 않는데 이 테스트는 NetworkSettings 를 직접 읽어야 하기 때문이다.
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
    void createAndStartContainerPublishesNoHostPortAtAll() {
        containerId = service.createAndStartContainer(
                ContainerRole.PREVIEW, 1L, "session-it", 1L, null, null);

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        var bindings = inspect.getNetworkSettings().getPorts().getBindings();

        // 발행이 없으면 바인딩 맵이 비었거나, 키가 있어도 값이 null 이다(둘 다 "게시 안 됨").
        assertThat(bindings.values().stream().filter(java.util.Objects::nonNull).toList())
                .as("프리뷰 컨테이너는 호스트 포트를 발행하지 않는다 (#358)")
                .isEmpty();
    }

    @Test
    void containerIpIsTheOnlyReachableAddressAndSurvivesRestart() {
        containerId = service.createAndStartContainer(
                ContainerRole.PREVIEW, 1L, "session-it", 1L, null, null);

        String ip = service.getContainerIp(containerId);
        assertThat(ip).as("게이트웨이가 프록시할 유일한 주소").isNotBlank();

        // 재시작은 stop+start 라 IP 가 바뀔 수 있다. 바뀌든 아니든 "지금의 주소"를 읽을 수 있어야
        // 하고, 그것이 세션에 반영되지 않으면 재시작 성공이 다음 요청의 502 가 된다(#71).
        service.restartContainer(containerId);
        assertThat(service.getContainerIp(containerId)).isNotBlank();

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        assertThat(inspect.getNetworkSettings().getPorts().getBindings().values().stream()
                .filter(java.util.Objects::nonNull).toList())
                .as("재시작 뒤에도 발행 포트는 없다")
                .isEmpty();
    }
}
