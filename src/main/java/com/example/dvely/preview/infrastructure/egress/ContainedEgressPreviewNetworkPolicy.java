package com.example.dvely.preview.infrastructure.egress;

import com.example.dvely.agent.infrastructure.docker.DockerClients;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
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
 *                     ├── 프록시(컨테이너 하나, 세션마다 tinyproxy 프로세스) ── bridge ── 바깥(허용목록만)
 *   프리뷰 B ── net-B ┘
 * </pre>
 *
 * <p>격리는 {@code enable_icc=false} 보다 강하다 — 서로 다른 네트워크라 라우팅 경로 자체가 없다
 * (TCP·ICMP 모두 차단을 확인했다). 게이트웨이는 {@code --internal} 네트워크의 컨테이너에도
 * 호스트에서 IP 로 닿으므로(#358) 프리뷰 서빙은 영향을 받지 않는다.</p>
 *
 * <p><b>프록시 컨테이너는 하나지만, 그 안에서 세션마다 tinyproxy 프로세스를 하나씩 띄운다</b>(#466).
 * 전에는 프록시 컨테이너 하나에 tinyproxy 프로세스도 하나(0.0.0.0 수신)였고, 그 {@code MaxClients}
 * 가 전역이었다 — 한 프리뷰가 연결을 상한만큼 붙잡고 있으면 다른 모든 프리뷰의 설치가 막혔다(실측
 * E7: A 가 유휴 연결 50~60개를 열면 B 의 설치 요청이 0/5). 세션 간 <i>접근</i>은 이미
 * {@code --internal} 네트워크 분리로 막혀 있었지만, <i>가용성</i>은 격리돼 있지 않았다.</p>
 *
 * <p>그래서 프록시 이미지·설정·필터는 그대로 두고(컨테이너를 하나 더 늘리면 이미지·권한·E6 격리
 * 검증을 처음부터 다시 해야 한다), 세션마다 전용 {@code Listen} IP·전용 {@code MaxClients}·전용
 * {@code PidFile} 을 가진 tinyproxy 프로세스를 그 컨테이너 안에서 추가로 띄운다. 별칭
 * {@code qeploy-egress} 는 네트워크마다 그 프록시 컨테이너의 IP 로 풀리므로({@link #attachNetwork}),
 * 세션별 프로세스가 "자기" 네트워크 쪽 IP 에만 수신해도 프리뷰 쪽 환경변수는 전혀 바뀌지 않는다.</p>
 *
 * <p>대가는 pids·메모리다. tinyproxy 는 연결마다 스레드를 쓰고, 스레드도 컨테이너의 cgroup pids
 * 상한에 들어간다. 세션마다 프로세스가 하나씩 늘어나므로 컨테이너 전체의 {@code PidsLimit}·메모리
 * 상한을 세션 수에 맞게 올려야 한다 — {@link PreviewEgressProperties#proxyPidsLimit()}·
 * {@link PreviewEgressProperties#proxyMemoryMb()} 의 산식 설명을 참고.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "qeploy.preview.egress", name = "enabled", havingValue = "true")
public class ContainedEgressPreviewNetworkPolicy implements PreviewNetworkPolicy {

    private static final String CONF_DIR = "/etc/tinyproxy";
    private static final String ALLOW_ENTRY = "qeploy-allow.txt";
    /** 프록시 컨테이너가 어떤 모양·상한으로 만들어졌는지. {@link #ensureProxyRunning()} 이 재사용 여부를 이것으로 정한다. */
    static final String SHAPE_LABEL = "qeploy.egressProxy.shape";

    // DockerClient 는 스프링 빈이 아니다 — 직접 만든다(DockerClients 참고).
    private final DockerClient dockerClient = DockerClients.createLocal();
    private final PreviewEgressProperties properties;

    /**
     * 프록시 컨테이너는 하나다. 동시에 여러 세션이 뜨거나 끝나며 그 컨테이너를 건드릴 수 있으므로
     * (만들기·네트워크 연결·세션별 tinyproxy 프로세스 기동·회수) 전부 이 락 하나로 직렬화한다.
     * 세션별 설정 파일을 서로 다른 이름으로 나눠도, docker exec 로 같은 컨테이너에 동시에 쓰고
     * 읽는 것 자체는 레이스이기 때문이다.
     */
    private final ReentrantLock proxyLock = new ReentrantLock();

    public ContainedEgressPreviewNetworkPolicy(PreviewEgressProperties properties) {
        this.properties = properties;
    }

    @Override
    public String attachNetwork(String previewSessionId) {
        String network = properties.networkName(previewSessionId);
        createInternalNetwork(network);
        startSessionProxy(previewSessionId, network);
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
     * 세션 전용 네트워크를 되돌린다. 세션의 tinyproxy 프로세스를 먼저 내리고, 프록시를 네트워크에서
     * 떼지 않으면 네트워크가 사용 중이라 지워지지 않는다.
     *
     * <p>실패해도 올리지 않는다 — 회수는 세션이 끝난 뒤의 뒷정리이고, 여기서 던지면 정작 컨테이너
     * 회수가 멈춘다. 남은 네트워크는 로그로 드러나고 다음 기동의 고아 정리가 가져간다. 같은 이유로
     * 이 메서드는 멱등이어야 한다(인터페이스 계약) — 세션의 tinyproxy 프로세스가 이미 없거나, 프록시
     * 컨테이너 자체가 {@link #ensureProxyRunning()} 의 재생성으로 통째로 바뀌어 있어도 조용히
     * 넘어간다.</p>
     */
    @Override
    public void release(String previewSessionId) {
        stopSessionProxy(previewSessionId);

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

    /**
     * 프록시 컨테이너를 준비하고, 그 안에 <b>이 세션 전용</b> tinyproxy 프로세스를 띄운다(#466).
     *
     * <p>컨테이너 생성·네트워크 연결·세션 프로세스 기동을 전부 {@link #proxyLock} 안에서 한다 —
     * 두 세션이 동시에 시작하면서 같은 컨테이너를 상대로 exec/copy 를 겹쳐 보내는 레이스를 막기
     * 위해서다. 락 구간에 {@link #awaitProxyListening} 의 재시도 대기(최대 몇 초)가 들어가지만,
     * 세션 시작은 어차피 컨테이너 기동 전체 경로에서 가장 느린 단계가 아니다.</p>
     */
    private void startSessionProxy(String previewSessionId, String network) {
        proxyLock.lock();
        try {
            ensureProxyRunning();
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(properties.proxyName())
                        .withNetworkId(network)
                        .withContainerNetwork(new ContainerNetwork().withAliases(properties.proxyAlias()))
                        .exec();
            } catch (ConflictException e) {
                log.debug("프록시가 이미 이 네트워크에 붙어 있음: network={}", network);
            }

            String listenIp = proxyIpOn(network);
            String sanitized = sanitizeSessionId(previewSessionId);
            String confPath = sessionConfPath(sanitized);
            String pidPath = sessionPidPath(sanitized);
            copyIn(properties.proxyName(), CONF_DIR, confEntry(sanitized), sessionTinyproxyConf(listenIp, pidPath));
            // 세션마다 새 tinyproxy 프로세스를 그냥 또 띄운다. attachNetwork 가 이미 뜬 세션에 다시
            // 불려도(예: 생성 재시도) 깨지지 않는다 — 이 프로세스가 쓰는 입력(허용목록·세션 상한·
            // listenIp)은 기동 중에는 바뀌지 않으므로, 낡은 프로세스가 이미 같은 IP:포트를 쥐고 있으면
            // 새로 띄우는 쪽이 bind 에 실패해 조용히 종료할 뿐이고, 그 아래 awaitProxyListening 은
            // (새 프로세스든 낡은 프로세스든) 그 IP:포트가 듣고 있다는 사실만 확인하므로 그대로 성공한다.
            detachExec(properties.proxyName(), "tinyproxy -d -c " + confPath + " >/dev/null 2>&1");
            awaitProxyListening(properties.proxyName(), listenIp);
            log.info("[PreviewEgress] 세션 프록시 기동: session={} network={} listen={}:{} maxClients={}",
                    previewSessionId, network, listenIp, properties.proxyPort(), properties.perSessionMaxClients());
        } finally {
            proxyLock.unlock();
        }
    }

    /**
     * 이 세션의 tinyproxy 프로세스를 내린다. pid 파일이 없거나(한 번도 뜨지 못했거나 이미 내려간
     * 경우) 가리키는 프로세스가 이미 죽어 있어도 조용히 넘어간다 — {@code release} 는 멱등해야
     * 하고, 여러 회수 경로(만료·강제 종료)가 같은 세션에 겹쳐 불릴 수 있다.
     *
     * <p>프록시 컨테이너 자체가 없는 경우(예: {@link #ensureProxyRunning()} 가 죽은 컨테이너를
     * 지우고 새로 만든 뒤, 그 사실을 모르는 오래된 세션이 회수되는 경우)도 여기서 걸러진다 — 기존
     * 회수 철학과 같다: 재생성된 프록시는 살아 있던 세션을 다시 연결해주지 않으므로, 그 세션은 이미
     * 죽은 프록시 자원을 들고 있었을 뿐이고 여기서 할 일이 없다.</p>
     */
    private void stopSessionProxy(String previewSessionId) {
        String sanitized = sanitizeSessionId(previewSessionId);
        String pidPath = sessionPidPath(sanitized);
        String confPath = sessionConfPath(sanitized);
        proxyLock.lock();
        try {
            // pid 파일이 있으면 그 pid 를 죽이고, 없거나 이미 죽은 프로세스를 가리켜도(kill 실패)
            // 전체 명령은 성공으로 끝낸다 — 그다음 rm -f 로 남은 conf/pid 파일을 정리한다.
            awaitExec(properties.proxyName(),
                    "[ -f " + pidPath + " ] && kill $(cat " + pidPath + ") 2>/dev/null; "
                            + "rm -f " + pidPath + " " + confPath + "; true");
        } catch (NotFoundException e) {
            log.debug("egress 프록시 컨테이너가 이미 없어 세션 프로세스 정리를 생략합니다: session={}", previewSessionId);
        } catch (RuntimeException e) {
            log.warn("세션 프록시 정리 실패(다음 프록시 재생성이 가져간다): session={} 사유={}", previewSessionId, e.toString());
        } finally {
            proxyLock.unlock();
        }
    }

    /**
     * 프록시가 없으면 만들고 띄운다. 이미 돌고 있으면 그대로 쓴다.
     *
     * <p>이미지에 tinyproxy 가 없으므로 기동 후 설치한다. 설치 자체는 바깥이 필요한데, 프록시는
     * {@code bridge} 에 있어 나갈 수 있다 — 나갈 수 없는 것은 프리뷰 쪽이다.</p>
     *
     * <p><b>여기서는 더 이상 전역 tinyproxy 리스너를 띄우지 않는다</b>(#466). 예전에는 이 메서드가
     * {@code Listen 0.0.0.0} 인 tinyproxy 프로세스 하나를 바로 띄워 모든 세션이 그것 하나를
     * 같이 썼다. 지금은 컨테이너와 허용목록 파일만 준비해두고, 실제 tinyproxy 프로세스는
     * {@link #startSessionProxy} 가 세션마다 따로 띄운다.</p>
     */
    private void ensureProxyRunning() {
        try {
            var inspect = dockerClient.inspectContainerCmd(properties.proxyName()).exec();
            var labels = inspect.getConfig().getLabels();
            String shape = labels == null ? null : labels.get(SHAPE_LABEL);
            boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());
            if (running && proxyShape().equals(shape)) {
                return;
            }
            // 살아 있어도 모양이 다르면 새로 만든다. #466 이전 코드가 만든 컨테이너는 0.0.0.0:포트 에
            // 전역 tinyproxy 를 띄워 두는데, 그게 남아 있으면 세션별 프로세스의 IP:포트 bind 가
            // 실패해(실측: "Could not create listening sockets") 배포 직후 모든 프리뷰 생성이 막힌다.
            // pids·메모리 상한도 컨테이너를 만들 때만 정해지므로 설정을 바꿨을 때도 여기서 반영된다.
            if (running) {
                log.info("[PreviewEgress] 프록시 컨테이너를 새 모양으로 다시 만듭니다: 현재={} 필요={}", shape, proxyShape());
            }
            // 죽어 있는 것을 되살리는 대신 지우고 새로 만든다 — 설정과 필터가 어느 상태인지
            // 알 수 없는 컨테이너를 egress 관문으로 쓰는 것보다 낫다. 살아 있던 세션들의 tinyproxy
            // 프로세스도 이 컨테이너와 함께 사라진다 — 재연결은 범위 밖이다(기존 동작 유지).
            dockerClient.removeContainerCmd(properties.proxyName()).withForce(true).exec();
        } catch (NotFoundException e) {
            log.debug("egress 프록시가 없습니다. 새로 만듭니다.");
        }

        CreateContainerResponse proxy = dockerClient.createContainerCmd(properties.proxyImage())
                .withName(properties.proxyName())
                .withHostConfig(HostConfig.newHostConfig()
                        .withMemory(properties.proxyMemoryMb() * 1024L * 1024L)
                        .withMemorySwap(properties.proxyMemoryMb() * 1024L * 1024L)
                        // 세션마다 tinyproxy 프로세스가 하나씩 뜨고, 그 프로세스의 연결마다 스레드가
                        // 또 하나 뜬다 — 스레드도 pids 카운트에 들어가므로 세션 수가 늘수록 이 상한도
                        // 같이 늘어야 한다. 산식은 PreviewEgressProperties#proxyPidsLimit() 참고.
                        .withPidsLimit((long) properties.proxyPidsLimit())
                        .withCapDrop(Capability.ALL)
                        // tinyproxy 는 바인딩 뒤 nobody 로 내려간다 — 그 전환에 SETUID/SETGID 가 필요하다.
                        .withCapAdd(Capability.SETUID, Capability.SETGID)
                        .withSecurityOpts(List.of("no-new-privileges")))
                .withLabels(Map.of("qeploy.egressProxy", "true", SHAPE_LABEL, proxyShape()))
                .withCmd("sleep", "infinity")
                .exec();
        dockerClient.startContainerCmd(proxy.getId()).exec();

        // 설치와 디렉터리 생성은 반드시 기다린다. 비동기로 두면 아래 복사가 앞질러
        // "Could not find the file /etc/tinyproxy" 로 죽는다 — dev 에서 실제로 그랬다.
        awaitExec(proxy.getId(), "apk add --no-cache tinyproxy >/dev/null 2>&1; mkdir -p " + CONF_DIR);
        copyIn(proxy.getId(), CONF_DIR, ALLOW_ENTRY, allowList());
        log.info("[PreviewEgress] 프록시 컨테이너 준비: name={} 허용 호스트 {}개 (세션별 tinyproxy 는 세션이 붙을 때 뜬다)",
                properties.proxyName(), properties.allowedHosts().size());
    }

    /**
     * 세션 전용 tinyproxy 설정. 예전의 전역 설정과 같은 필터·매칭 규칙을 쓰지만
     * {@code Listen}·{@code MaxClients}·{@code PidFile} 세 가지만 세션마다 다르다:
     * <ul>
     *   <li>{@code Listen} 은 {@code 0.0.0.0} 이 아니라 이 세션 네트워크에서의 프록시 자체 IP다 —
     *   그래서 이 프로세스로 들어오는 연결은 이 세션 네트워크를 거쳐온 것뿐이다.</li>
     *   <li>{@code MaxClients} 는 세션별 상한이다 — 전역이 아니므로 한 세션이 상한을 다 써도
     *   다른 세션의 프로세스·연결에는 영향이 없다(#466 이 고치는 바로 그 지점).</li>
     *   <li>{@code PidFile} 도 세션마다 달라야 한다 — 같으면 나중에 뜨는 프로세스가 앞 프로세스의
     *   pid 파일을 덮어써서, 회수 시점에 엉뚱한(또는 이미 죽은) pid 를 죽이려 들게 된다.</li>
     * </ul>
     *
     * <p>{@code Filter} 가 {@code Filter*} 옵션들보다 앞에 와야 한다 — tinyproxy 1.11 은 순서가
     * 어긋나면 파일을 거부하고, 필터 없이 뜨는 대신 <b>종료한다</b>(열린 채로 도는 것보다 낫다).</p>
     */
    String sessionTinyproxyConf(String listenIp, String pidFilePath) {
        return """
                User nobody
                Group nobody
                Port %d
                Listen %s
                Timeout 600
                MaxClients %d
                PidFile "%s"
                Allow 0.0.0.0/0
                ConnectPort 443
                Filter "%s"
                FilterExtended On
                FilterURLs Off
                FilterDefaultDeny Yes
                """.formatted(
                properties.proxyPort(), listenIp, properties.perSessionMaxClients(), pidFilePath,
                CONF_DIR + "/" + ALLOW_ENTRY);
    }

    /**
     * 컨테이너 단위로 정해지는 것만 담는다 — 세션별 프로세스 모양과 pids·메모리 상한. 세션 상한
     * ({@code MaxClients})은 세션 설정 파일에 들어가 다음 세션부터 바로 반영되므로 넣지 않는다.
     */
    String proxyShape() {
        return "per-session;pids=" + properties.proxyPidsLimit() + ";memory-mb=" + properties.proxyMemoryMb();
    }

    /** 앵커를 붙이고 점을 이스케이프한다 — {@code github.com} 이 {@code github.com.evil.test} 까지 열지 않게. */
    String allowList() {
        return properties.allowedHosts().stream()
                .map(host -> "^" + host.replace(".", "\\.") + "$")
                .collect(Collectors.joining("\n")) + "\n";
    }

    /**
     * 세션 ID 를 컨테이너 안의 파일 이름으로 써도 안전한 문자만 남긴다.
     *
     * <p>지금 세션 ID 는 {@code UUID.randomUUID()} 라 원래도 안전하지만, 이 값은 호출부가 바뀌면
     * 형태가 달라질 수 있는 입력이다 — 파일 이름을 만드는 코드는 입력의 출처를 믿지 않는다. 허용
     * 문자 밖은 모두 {@code _} 로 바꾸므로 {@code /}·{@code ..} 같은 경로 조작 문자는 사라지고,
     * 빈 결과는 {@code unknown} 으로 대체해 항상 비어있지 않은 파일 이름이 나온다.</p>
     */
    static String sanitizeSessionId(String sessionId) {
        if (sessionId == null) {
            return "unknown";
        }
        String cleaned = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String confEntry(String sanitizedSessionId) {
        return "s-" + sanitizedSessionId + ".conf";
    }

    private static String sessionConfPath(String sanitizedSessionId) {
        return CONF_DIR + "/" + confEntry(sanitizedSessionId);
    }

    private static String sessionPidPath(String sanitizedSessionId) {
        return CONF_DIR + "/s-" + sanitizedSessionId + ".pid";
    }

    /**
     * 프록시 컨테이너가 이 세션 네트워크에서 받은 IP. 별칭 {@code qeploy-egress} 는 네트워크마다
     * 이 IP 로 풀리므로, 세션 전용 tinyproxy 가 여기에만 {@code Listen} 해도 프리뷰가 평소처럼
     * {@code qeploy-egress} 로 접근할 수 있다.
     */
    private String proxyIpOn(String network) {
        var networks = dockerClient.inspectContainerCmd(properties.proxyName()).exec()
                .getNetworkSettings().getNetworks();
        ContainerNetwork proxyNetwork = networks == null ? null : networks.get(network);
        String ip = proxyNetwork == null ? null : proxyNetwork.getIpAddress();
        if (ip == null || ip.isBlank()) {
            // 여기서 조용히 넘어가면 세션 tinyproxy 가 빈 IP 로 뜨려다 실패하고, 그 실패는
            // "설치가 안 된다" 로만 보여 원인이 드러나지 않는다 — 바로 던진다.
            throw new IllegalStateException(
                    "프록시의 세션 네트워크 IP 를 확인할 수 없습니다. network=" + network);
        }
        return ip;
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
     * 이 세션의 tinyproxy 가 자기 IP:포트에서 실제로 받을 준비가 될 때까지 기다린다.
     *
     * <p>포트 번호만 보면 안 된다 — 모든 세션의 tinyproxy 가 같은 포트({@code properties.proxyPort()})
     * 를 쓰고 서로 다른 IP 에만 {@code Listen} 하므로, 다른 세션이 이미 그 포트에서 듣고 있으면
     * 포트만 확인하는 검사는 "이 세션도 준비됐다" 고 잘못 판단할 수 있다. 그래서 {@code ip:port}
     * 조합을 정확히 찾는다.</p>
     *
     * <p>기다리지 않으면 이 세션의 첫 요청이 아직 뜨지 않은 tinyproxy 를 쳐서 실패한다 — 그 실패는
     * "설치가 안 된다" 로만 보여 프록시가 원인이라는 것이 드러나지 않는다.</p>
     *
     * <p>뜨지 않으면 던진다. 프록시 없이 프리뷰를 올리면 그 컨테이너는 바깥과 완전히 단절돼
     * 있어(internal 네트워크) 어차피 아무것도 설치하지 못한다.</p>
     */
    private void awaitProxyListening(String containerId, String listenIp) {
        String needle = listenIp + ":" + properties.proxyPort() + " ";
        for (int attempt = 0; attempt < 30; attempt++) {
            var created = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "netstat -tln 2>/dev/null | grep -qF '" + needle + "'")
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
                "egress 프록시가 " + listenIp + ":" + properties.proxyPort() + " 에서 응답하지 않습니다. "
                        + "프리뷰가 바깥에 닿을 수 없습니다.");
    }

    /** tinyproxy 는 기동 때 설정을 한 번만 읽고 잘못된 파일이면 종료한다 — 그래서 기동 전에 넣는다. */
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
