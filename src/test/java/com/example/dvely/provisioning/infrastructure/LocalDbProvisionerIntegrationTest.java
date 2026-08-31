package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService.ExecResult;
import com.example.dvely.provisioning.application.port.out.ProvisionResult;
import com.example.dvely.provisioning.application.port.out.ProvisionSpec;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 실제 Docker 로 LOCAL DB 프로비저닝을 검증한다. 모의로는 "우리가 docker 에 무엇을 보내는지"만
 * 증명할 수 있고, "그래서 DB 가 실제로 접속을 받는가"는 데몬 쪽 사실이라 실측해야 한다 —
 * PreviewWorkspace 의 serve 버그가 정확히 그 경계 너머에 있었다(코드는 성공인데 실제로는 옛
 * serve 가 응답). "READY = 진짜 접속 가능"을 이 테스트가 고정한다.
 *
 * <p>실행 중인 Docker 데몬과 postgres:16-alpine 이미지를 전제한다 — 프리뷰 컨테이너 코드가
 * node:20-alpine 을 전제하는 것과 같다.
 */
class LocalDbProvisionerIntegrationTest {

    private DockerClient dockerClient;
    private DockerContainerService dockerService;
    private LocalDbProvisioner provisioner;
    private String appContainerId;
    private ProvisionResult provisioned;

    @BeforeEach
    void setUp() {
        String dockerHost = System.getProperty("os.name").toLowerCase().contains("win")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost).build();
        var httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).build();
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
        dockerService = new DockerContainerService();
        provisioner = new LocalDbProvisioner(dockerService);
    }

    @AfterEach
    void tearDown() {
        if (appContainerId != null) {
            try { dockerClient.removeContainerCmd(appContainerId).withForce(true).exec(); } catch (RuntimeException ignored) {}
        }
        if (provisioned != null) {
            try { provisioner.deprovision(provisioned.resourceId()); } catch (RuntimeException ignored) {}
        }
    }

    private final String sessionId = "itest-" + System.nanoTime();

    @Test
    void aProvisionedPostgresActuallyAcceptsConnectionsFromTheAppSibling() {
        // 세션 네트워크 + DB 컨테이너를 실제로 띄운다.
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), sessionId);

        assertThat(provisioned.host()).isEqualTo("db");
        assertThat(provisioned.port()).isEqualTo(5432);
        assertThat(provisioned.password()).isNotBlank();

        // 앱 역할 컨테이너를 같은 세션 네트워크에 붙여 실제로 쿼리한다 — 이게 "READY = 접속 가능".
        String network = "qeploy-db-" + sessionId;
        appContainerId = startAppOn(network);
        // 앱→DB 는 네트워크 너머라, 전체 스위트 부하에서는 DNS 전파·준비에 몇 초 시차가 날 수
        // 있다. readyProbe(DB 내부 pg_isready)가 통과해도 앱 쪽 첫 붙기는 흔들릴 수 있어 재시도한다.
        ExecResult query = null;
        for (int i = 0; i < 15; i++) {
            query = dockerService.execWithExitCode(appContainerId,
                    "PGPASSWORD='" + provisioned.password() + "' psql -h db -U app -d app -tAc \"SELECT 42\"");
            if (query.succeeded() && query.output().contains("42")) break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertThat(query.succeeded()).isTrue();
        assertThat(query.output()).contains("42");
    }

    @Test
    void aContainerOnAnotherNetworkCannotReachThisDatabase() {
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), sessionId);

        // 다른 세션(다른 네트워크)의 컨테이너는 이 DB 이름을 해석조차 못 해야 한다.
        String otherNet = dockerService.createSessionNetwork("other-" + System.nanoTime());
        String intruder = startAppOn(otherNet);
        try {
            ExecResult probe = dockerService.execWithExitCode(intruder,
                    "PGPASSWORD='x' psql -h db -U app -d app -c 'SELECT 1' 2>&1");
            assertThat(probe.succeeded()).isFalse();
            assertThat(probe.output()).containsIgnoringCase("could not translate host name");
        } finally {
            dockerClient.removeContainerCmd(intruder).withForce(true).exec();
            dockerService.removeSessionNetwork(otherNet);
        }
    }

    @Test
    void deprovisionRemovesTheContainerAndNetwork() {
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), sessionId);
        String resourceId = provisioned.resourceId();

        provisioner.deprovision(resourceId);
        provisioned = null;  // tearDown 재삭제 방지

        assertThatThrownBy(() -> dockerClient.inspectContainerCmd(resourceId).exec())
                .isInstanceOf(com.github.dockerjava.api.exception.NotFoundException.class);
    }

    private String startAppOn(String network) {
        var c = dockerClient.createContainerCmd("postgres:16-alpine")
                .withHostConfig(HostConfig.newHostConfig()
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID)
                        .withNetworkMode(network))
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        dockerClient.startContainerCmd(c.getId()).exec();
        return c.getId();
    }
}
