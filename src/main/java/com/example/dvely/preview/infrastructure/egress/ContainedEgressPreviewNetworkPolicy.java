package com.example.dvely.preview.infrastructure.egress;

import com.example.dvely.agent.infrastructure.docker.DockerClients;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 프리뷰의 바깥 통신을 허용목록으로 좁힌다 (#332 4단계).
 *
 * <p><b>모양이 이렇게 된 이유는 실측이다</b>(2026-09-15). 공유 네트워크 하나로는 두 성질이
 * 동시에 서지 않는다 — {@code enable_icc=false} 를 켜면 프리뷰가 프록시에도 닿지 못하고, 끄면
 * 프리뷰끼리 서로 닿는다. 그래서 <b>프리뷰마다 전용 {@code --internal} 네트워크</b>를 주고,
 * <b>프록시 하나</b>가 그 전부와 {@code bridge} 에 붙는다.</p>
 *
 * <pre>
 *   프리뷰 A ── net-A ┐
 *                     ├── 프록시(공유) ── bridge ── 바깥(허용목록만)
 *   프리뷰 B ── net-B ┘
 * </pre>
 *
 * <p>격리는 {@code enable_icc=false} 보다 강하다 — 서로 다른 네트워크라 라우팅 경로 자체가 없다
 * (TCP·ICMP 모두 차단을 확인했다). 게이트웨이는 {@code --internal} 네트워크의 컨테이너에도
 * 호스트에서 IP 로 닿으므로(#358) 프리뷰 서빙은 영향을 받지 않는다.</p>
 *
 * <p>프록시는 하나만 쓴다. 프리뷰마다 띄우면 128MB 가 프리뷰 수만큼 늘어나는데, 필터가 같으므로
 * 나눌 이유가 없다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "qeploy.preview.egress", name = "enabled", havingValue = "true")
public class ContainedEgressPreviewNetworkPolicy implements PreviewNetworkPolicy {

    private static final long PROXY_MEMORY_BYTES = 128L << 20;
    private static final String CONF_DIR = "/etc/tinyproxy";
    private static final String CONF_ENTRY = "qeploy.conf";
    private static final String ALLOW_ENTRY = "qeploy-allow.txt";
    private static final String CONF_PATH = CONF_DIR + "/" + CONF_ENTRY;

    // DockerClient 는 스프링 빈이 아니다 — 직접 만든다(DockerClients 참고).
    private final DockerClient dockerClient = DockerClients.createLocal();
    private final PreviewEgressProperties properties;

    /** 프록시는 하나다. 동시에 여러 프리뷰가 뜰 때 두 번 만들지 않게 한 스레드만 준비한다. */
    private final ReentrantLock proxyLock = new ReentrantLock();

    public ContainedEgressPreviewNetworkPolicy(PreviewEgressProperties properties) {
        this.properties = properties;
    }

    @Override
    public String attachNetwork(String previewSessionId) {
        String network = properties.networkName(previewSessionId);
        createInternalNetwork(network);
        connectProxyTo(network);
        return network;
    }

    @Override
    public List<String> environment() {
        String url = properties.proxyUrl();
        // 대소문자 둘 다 준다 — 도구마다 보는 이름이 다르다(curl 은 소문자, npm 은 대문자를 먼저 본다).
        // JAVA_TOOL_OPTIONS 는 JVM 이 환경변수 프록시를 스스로 읽지 않기 때문에 필요하다. gradle 이
        // 배포본과 의존성을 받는 경로가 여기에 걸린다.
        return List.of(
                "HTTP_PROXY=" + url,
                "HTTPS_PROXY=" + url,
                "http_proxy=" + url,
                "https_proxy=" + url,
                "NO_PROXY=",
                "JAVA_TOOL_OPTIONS="
                        + "-Dhttp.proxyHost=" + properties.proxyAlias() + " -Dhttp.proxyPort=" + properties.proxyPort()
                        + " -Dhttps.proxyHost=" + properties.proxyAlias() + " -Dhttps.proxyPort=" + properties.proxyPort());
    }

    /**
     * 세션 전용 네트워크를 되돌린다. 프록시를 먼저 떼지 않으면 네트워크가 사용 중이라 지워지지 않는다.
     *
     * <p>실패해도 올리지 않는다 — 회수는 세션이 끝난 뒤의 뒷정리이고, 여기서 던지면 정작 컨테이너
     * 회수가 멈춘다. 남은 네트워크는 로그로 드러나고 다음 기동의 고아 정리가 가져간다.</p>
     */
    @Override
    public void release(String previewSessionId) {
        String network = properties.networkName(previewSessionId);
        try {
            dockerClient.disconnectFromNetworkCmd()
                    .withContainerId(properties.proxyName())
                    .withNetworkId(network)
                    .withForce(true)
                    .exec();
        } catch (NotFoundException | IllegalStateException e) {
            log.debug("egress 네트워크 연결 해제 생략(이미 없음): network={}", network);
        } catch (RuntimeException e) {
            log.warn("egress 네트워크 연결 해제 실패: network={} 사유={}", network, e.toString());
        }
        try {
            dockerClient.removeNetworkCmd(network).exec();
            log.info("[PreviewEgress] 세션 네트워크 제거: {}", network);
        } catch (NotFoundException e) {
            log.debug("egress 네트워크가 이미 없습니다: network={}", network);
        } catch (RuntimeException e) {
            log.warn("egress 네트워크 제거 실패(고아로 남는다): network={} 사유={}", network, e.toString());
        }
    }

    private void createInternalNetwork(String name) {
        boolean exists = dockerClient.listNetworksCmd().withNameFilter(name).exec().stream()
                .anyMatch(network -> name.equals(network.getName()));
        if (exists) {
            return;
        }
        try {
            // internal 이 바깥으로 나가는 경로를 없앤다. icc 는 건드리지 않는다 — 이 네트워크에는
            // 프리뷰 하나와 프록시만 있고, 프리뷰끼리는 애초에 같은 네트워크에 있지 않다.
            dockerClient.createNetworkCmd().withName(name).withDriver("bridge").withInternal(true).exec();
            log.info("[PreviewEgress] 세션 네트워크 생성: {}", name);
        } catch (ConflictException e) {
            log.debug("egress 네트워크 동시 생성 레이스: name={}", name);
        }
    }

    private void connectProxyTo(String network) {
        proxyLock.lock();
        try {
            ensureProxyRunning();
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(properties.proxyName())
                        .withNetworkId(network)
                        .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork()
                                .withAliases(properties.proxyAlias()))
                        .exec();
            } catch (ConflictException e) {
                log.debug("프록시가 이미 이 네트워크에 붙어 있음: network={}", network);
            }
        } finally {
            proxyLock.unlock();
        }
    }

    /**
     * 프록시가 없으면 만들고 띄운다. 이미 돌고 있으면 그대로 쓴다.
     *
     * <p>이미지에 tinyproxy 가 없으므로 기동 후 설치한다. 설치 자체는 바깥이 필요한데, 프록시는
     * {@code bridge} 에 있어 나갈 수 있다 — 나갈 수 없는 것은 프리뷰 쪽이다.</p>
     */
    private void ensureProxyRunning() {
        try {
            var inspect = dockerClient.inspectContainerCmd(properties.proxyName()).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return;
            }
            // 죽어 있는 것을 되살리는 대신 지우고 새로 만든다 — 설정과 필터가 어느 상태인지
            // 알 수 없는 컨테이너를 egress 관문으로 쓰는 것보다 낫다.
            dockerClient.removeContainerCmd(properties.proxyName()).withForce(true).exec();
        } catch (NotFoundException e) {
            log.debug("egress 프록시가 없습니다. 새로 만듭니다.");
        }

        CreateContainerResponse proxy = dockerClient.createContainerCmd(properties.proxyImage())
                .withName(properties.proxyName())
                .withHostConfig(HostConfig.newHostConfig()
                        .withMemory(PROXY_MEMORY_BYTES)
                        .withMemorySwap(PROXY_MEMORY_BYTES)
                        .withPidsLimit(64L)
                        .withCapDrop(Capability.ALL)
                        // tinyproxy 는 바인딩 뒤 nobody 로 내려간다 — 그 전환에 SETUID/SETGID 가 필요하다.
                        .withCapAdd(Capability.SETUID, Capability.SETGID)
                        .withSecurityOpts(List.of("no-new-privileges")))
                .withLabels(Map.of("qeploy.egressProxy", "true"))
                .withCmd("sleep", "infinity")
                .exec();
        dockerClient.startContainerCmd(proxy.getId()).exec();

        // 설치와 디렉터리 생성은 반드시 기다린다. 비동기로 두면 아래 복사가 앞질러
        // "Could not find the file /etc/tinyproxy" 로 죽는다 — dev 에서 실제로 그랬다.
        awaitExec(proxy.getId(), "apk add --no-cache tinyproxy >/dev/null 2>&1; mkdir -p " + CONF_DIR);
        copyIn(proxy.getId(), CONF_DIR, ALLOW_ENTRY, allowList());
        copyIn(proxy.getId(), CONF_DIR, CONF_ENTRY, tinyproxyConf());
        // 기동은 기다리지 않는다 — tinyproxy 는 포그라운드로 도는 프로세스다.
        detachExec(proxy.getId(), "tinyproxy -d -c " + CONF_PATH + " >/dev/null 2>&1");
        awaitProxyListening(proxy.getId());
        log.info("[PreviewEgress] 프록시 기동: name={} 허용 호스트 {}개",
                properties.proxyName(), properties.allowedHosts().size());
    }

    /**
     * {@code Filter} 가 {@code Filter*} 옵션들보다 앞에 와야 한다 — tinyproxy 1.11 은 순서가
     * 어긋나면 파일을 거부하고, 필터 없이 뜨는 대신 <b>종료한다</b>(열린 채로 도는 것보다 낫다).
     */
    String tinyproxyConf() {
        return """
                User nobody
                Group nobody
                Port %d
                Listen 0.0.0.0
                Timeout 600
                MaxClients 50
                Allow 0.0.0.0/0
                ConnectPort 443
                Filter "%s"
                FilterExtended On
                FilterURLs Off
                FilterDefaultDeny Yes
                """.formatted(properties.proxyPort(), CONF_DIR + "/" + ALLOW_ENTRY);
    }

    /** 앵커를 붙이고 점을 이스케이프한다 — {@code github.com} 이 {@code github.com.evil.test} 까지 열지 않게. */
    String allowList() {
        return properties.allowedHosts().stream()
                .map(host -> "^" + host.replace(".", "\\.") + "$")
                .collect(Collectors.joining("\n")) + "\n";
    }

    /** 끝날 때까지 기다린다. 뒤따르는 단계가 이 결과에 기대는 경우에 쓴다. */
    private void awaitExec(String containerId, String command) {
        var created = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        try {
            dockerClient.execStartCmd(created.getId())
                    .withDetach(false)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>())
                    .awaitCompletion(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("egress 프록시 준비가 중단됐습니다.", e);
        }
    }

    private void detachExec(String containerId, String command) {
        var created = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", command)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .exec();
        dockerClient.execStartCmd(created.getId()).withDetach(true)
                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>());
    }

    /**
     * tinyproxy 가 실제로 받을 준비가 될 때까지 기다린다.
     *
     * <p>기다리지 않으면 첫 프리뷰의 {@code apk}·{@code npm} 이 아직 없는 프록시를 쳐서 실패한다 —
     * 그 실패는 "설치가 안 된다" 로만 보여 프록시가 원인이라는 것이 드러나지 않는다.</p>
     *
     * <p>뜨지 않으면 던진다. 프록시 없이 프리뷰를 올리면 그 컨테이너는 바깥과 완전히 단절돼
     * 있어(internal 네트워크) 어차피 아무것도 설치하지 못한다.</p>
     */
    private void awaitProxyListening(String containerId) {
        for (int attempt = 0; attempt < 30; attempt++) {
            var created = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "netstat -tln 2>/dev/null | grep -q ':" + properties.proxyPort() + " '")
                    .exec();
            try {
                dockerClient.execStartCmd(created.getId()).withDetach(false)
                        .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>())
                        .awaitCompletion(5, java.util.concurrent.TimeUnit.SECONDS);
                Long exit = dockerClient.inspectExecCmd(created.getId()).exec().getExitCodeLong();
                if (exit != null && exit == 0L) {
                    return;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("egress 프록시 대기가 중단됐습니다.", e);
            }
        }
        throw new IllegalStateException(
                "egress 프록시가 " + properties.proxyPort() + " 에서 응답하지 않습니다. 프리뷰가 바깥에 닿을 수 없습니다.");
    }

    /** tinyproxy 는 부팅 때 설정을 한 번만 읽고 잘못된 파일이면 종료한다 — 그래서 기동 전에 넣는다. */
    private void copyIn(String containerId, String dir, String name, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(tar)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(body.length);
            entry.setMode(0644);
            out.putArchiveEntry(entry);
            out.write(body);
            out.closeArchiveEntry();
        } catch (Exception e) {
            throw new IllegalStateException("egress 프록시 설정을 만들지 못했습니다: " + name, e);
        }
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath(dir)
                .withTarInputStream(new ByteArrayInputStream(tar.toByteArray()))
                .exec();
    }
}
