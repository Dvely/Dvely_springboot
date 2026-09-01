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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 실제 Docker 로 LOCAL DB 프로비저닝을 검증한다. 모의로는 "우리가 docker 에 무엇을 보내는지"만
 * 증명할 수 있고, "그래서 DB 가 실제로 접속을 받는가"는 데몬 쪽 사실이라 실측해야 한다 —
 * PreviewWorkspace 의 serve 버그가 정확히 그 경계 너머에 있었다(코드는 성공인데 실제로는 옛
 * serve 가 응답). "READY = 진짜 접속 가능"을 이 테스트가 고정한다.
 *
 * <p>실행 중인 Docker 데몬과 postgres:16-alpine 이미지를 전제한다 — 프리뷰 컨테이너 코드가
 * node:20-alpine 을 전제하는 것과 같다.
 */
// 이 테스트만 저장소에서 유일하게 실행 중인 Docker 데몬을 요구한다(나머지는 전부
// DockerContainerService 를 모의한다). CI 는 전 테스트를 한 스위트로 돌리는데, 이 테스트는
// 정확히 그 풀스위트 부하에서 컨테이너·DNS 경합으로 flaky 했다(그래서 앱→DB 쿼리에 재시도
// 루프가 있다). 매 CI 마다 postgres·mysql 이미지를 pull 하는 비용도 있다. 그래서 기본은
// 건너뛰고, 로컬에서 -Ddocker.it=true 로 명시적으로 켤 때만 실측한다.
//   ./gradlew test --tests "*LocalDbProvisionerIntegrationTest" -Ddocker.it=true
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
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

    @Test
    void aProvisionedPostgresActuallyAcceptsConnectionsFromTheAppSibling() {
        // 프리뷰 앱 역할의 컨테이너를 먼저 띄우고, 그 실제 ID 를 provision 에 넘긴다 — 프로덕션과
        // 같은 경로다(provision 이 이 컨테이너를 세션 네트워크에 직접 붙인다).
        appContainerId = startPreviewApp("postgres:16-alpine");
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), appContainerId);

        assertThat(provisioned.host()).isEqualTo("db");
        assertThat(provisioned.port()).isEqualTo(5432);
        assertThat(provisioned.password()).isNotBlank();

        // provision 이 이미 앱을 세션 네트워크에 붙였다 — 앱에서 바로 DB 로 쿼리한다. 이게 "접속 가능".
        // 전체 스위트 부하에서는 DNS 전파·준비에 몇 초 시차가 날 수 있어 재시도한다.
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

    /**
     * MySQL 준비 핑은 이제 컨테이너 env 의 MYSQL_PASSWORD 를 셸에서 확장한다(명령줄에 평문 비번을
     * 안 남기려고). provision 이 정상 리턴한다는 것은 그 env 확장 핑이 통과했다는 뜻이므로, 이
     * 테스트가 그 경로를 지킨다.
     */
    @Test
    void aProvisionedMysqlBecomesReadyViaEnvExpandedProbe() {
        appContainerId = startPreviewApp("mysql:8.4");
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.MYSQL), appContainerId);

        assertThat(provisioned.host()).isEqualTo("db");
        assertThat(provisioned.port()).isEqualTo(3306);
        assertThat(provisioned.password()).isNotBlank();

        // 앱(mysql 클라이언트 보유)에서 실제로 붙어 쿼리한다.
        ExecResult query = null;
        for (int i = 0; i < 20; i++) {
            query = dockerService.execWithExitCode(appContainerId,
                    "mysql -h db -u app -p'" + provisioned.password() + "' -N -e 'SELECT 42' app 2>/dev/null");
            if (query.succeeded() && query.output().contains("42")) break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertThat(query.succeeded()).isTrue();
        assertThat(query.output()).contains("42");
    }

    @Test
    void aContainerOnAnotherNetworkCannotReachThisDatabase() {
        appContainerId = startPreviewApp("postgres:16-alpine");
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), appContainerId);

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
        appContainerId = startPreviewApp("postgres:16-alpine");
        provisioned = provisioner.provision(
                new ProvisionSpec(1L, DatabaseEngine.POSTGRESQL), appContainerId);
        String resourceId = provisioned.resourceId();

        provisioner.deprovision(resourceId);
        provisioned = null;  // tearDown 재삭제 방지

        assertThatThrownBy(() -> dockerClient.inspectContainerCmd(resourceId).exec())
                .isInstanceOf(com.github.dockerjava.api.exception.NotFoundException.class);
    }

    /** 프리뷰 앱 역할 — 기본 네트워크에 떠 있는 상태로 만든다. provision 이 세션 네트워크에 붙인다. */
    private String startPreviewApp(String image) {
        var c = dockerClient.createContainerCmd(image)
                .withHostConfig(HostConfig.newHostConfig()
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID))
                .withEntrypoint("sh", "-c")
                .withCmd("tail -f /dev/null")
                .exec();
        dockerClient.startContainerCmd(c.getId()).exec();
        return c.getId();
    }

    /** 지정 네트워크에 붙여 띄우는 침입자/보조 컨테이너. */
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
