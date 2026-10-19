package com.example.dvely.preview.infrastructure.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.docker.DockerClients;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Issue #466 의 실측(E7)을 재현하고 고쳤는지 확인한다: 세션 하나가 egress 프록시 연결을 상한만큼
 * 쥐고 있어도 <b>다른 세션</b>의 요청은 영향을 받지 않아야 한다.
 *
 * <p>실측 장치를 그대로 옮긴다 — 세션 A 가 아무것도 보내지 않는 TCP 연결을 {@code perSessionMaxClients}
 * 개 열어둔다(실측 공격과 같은 모양: 연결만 열고 아무것도 보내지 않는다, 권한·프로토콜 지식이 필요
 * 없다). 그 상태에서:
 * <ul>
 *   <li>A 자신이 상한을 넘겨 하나 더 열면, TCP 연결 자체는 커널이 받아주지만(핸드셰이크는 accept()
 *   와 무관하게 끝난다) tinyproxy 의 accept 스레드가 전부 유휴 연결에 묶여 있어 응답이 오지 않는다
 *   — 실측에서 관찰한 "10초 타임아웃" 과 같은 모양이라 여기서도 "응답 없음" 으로 확인한다.</li>
 *   <li>B 는 <b>별도 프로세스</b>이므로 A 의 상태와 무관하게 즉시 응답한다. 인터넷 접근 없이도
 *   결정적으로 확인하려고 허용목록에 없는 호스트로 CONNECT 를 보낸다 — tinyproxy 는
 *   {@code FilterDefaultDeny} 를 아웃바운드 연결을 열기 <b>전에</b> 검사하므로 403 이 바로
 *   온다(세션별 프로세스로 바뀐 뒤에도 E6 격리가 그대로라는 회귀 확인도 겸한다).</li>
 * </ul>
 *
 * <p><b>테스트 러너(JVM 을 띄운 호스트)가 아니라 각 세션 네트워크에 붙은 헬퍼 컨테이너에서 연결한다</b>
 * — 처음에는 호스트에서 {@code java.net.Socket} 으로 프록시의 세션별 IP 에 직접 붙는 방식으로
 * 짰지만, Docker 환경에 따라 호스트가 {@code --internal} 브리지의 컨테이너 IP 로 바로 라우팅되지
 * 않는 경우가 있어(가상화 계층을 쓰는 Docker 배포가 대표적이다) 그 경로 자체가 막혀 거짓 실패가
 * 났다. 실제 프리뷰도 호스트가 아니라 그 세션 네트워크에 붙은 컨테이너이므로, 헬퍼 컨테이너로
 * 똑같이 하는 쪽이 더 사실에 가깝기도 하다 — 별칭 {@code qeploy-egress} 로 접근하는 것도 실제
 * 프리뷰와 동일하다.</p>
 *
 * <p>프록시 컨테이너 이름·네트워크 접두를 운영 기본값과 다르게 둔다 — 이 테스트가 실제로 떠 있는
 * dev 프록시를 건드리거나 반대로 그것에 끼어드는 일이 없게 한다. {@code @AfterEach} 가 이 테스트
 * 전용 컨테이너를 전부 강제로 지워 매 실행이 깨끗한 상태에서 시작한다.</p>
 */
@EnabledIfSystemProperty(named = "docker.it", matches = "true")
class ContainedEgressPreviewNetworkPolicyIntegrationTest {

    private static final int PER_SESSION_MAX_CLIENTS = 3;
    private static final String PROXY_NAME = "qeploy-egress-it-466";
    private static final String NETWORK_PREFIX = "qeploy-egress-it-466-";
    private static final String SESSION_A = "session-a";
    private static final String SESSION_B = "session-b";
    private static final String HELPER_IMAGE = "alpine:3.20";
    private static final String HELPER_A = "qeploy-egress-it-466-helper-a";
    private static final String HELPER_B = "qeploy-egress-it-466-helper-b";
    // 허용목록에 없는 호스트 — FilterDefaultDeny 가 걸러서, 실제 바깥 연결 없이도 결정적으로
    // "프록시가 즉시 응답했다" 를 확인할 수 있다(테스트 환경의 인터넷 접근 여부와 무관하다).
    private static final String DISALLOWED_TARGET = "disallowed.invalid:443";

    private final DockerClient dockerClient = DockerClients.createLocal();
    private ContainedEgressPreviewNetworkPolicy policy;

    @BeforeEach
    void setUp() {
        PreviewEgressProperties properties = new PreviewEgressProperties(
                true, "alpine:3.20", PROXY_NAME, "qeploy-egress", 8888,
                NETWORK_PREFIX, List.of("github.com"), PER_SESSION_MAX_CLIENTS, 64, 128);
        policy = new ContainedEgressPreviewNetworkPolicy(properties);
    }

    /**
     * 헬퍼 컨테이너, 세션 둘, 이 테스트 전용 프록시 컨테이너를 모두 지운다. 하나가 실패해도 나머지는
     * 계속 시도한다 — 운영 {@code release} 의 "회수는 던지지 않는다" 철학을, 테스트 뒷정리에서는
     * "그래도 최대한 지운다" 로 적용한다.
     *
     * <p>순서가 중요하다 — 헬퍼 컨테이너가 세션 네트워크에 붙어 있는 동안 {@code policy.release}
     * 가 그 네트워크를 지우려 하면 "네트워크가 사용 중" 으로 조용히 실패하고(운영 코드의 설계대로
     * 던지지 않는다) 네트워크가 고아로 남는다 — 그래서 헬퍼부터 지운다.</p>
     */
    @AfterEach
    void tearDown() {
        bestEffort(() -> dockerClient.removeContainerCmd(HELPER_A).withForce(true).exec());
        bestEffort(() -> dockerClient.removeContainerCmd(HELPER_B).withForce(true).exec());
        bestEffort(() -> policy.release(SESSION_A));
        bestEffort(() -> policy.release(SESSION_B));
        bestEffort(() -> dockerClient.removeContainerCmd(PROXY_NAME).withForce(true).exec());
    }

    @Test
    @DisplayName("한 세션이 프록시 연결을 상한만큼 쥐고 있어도, 다른 세션의 프록시 요청은 영향을 받지 않는다")
    void sessionCapIsIsolatedAcrossSessions() throws Exception {
        String networkA = policy.attachNetwork(SESSION_A);
        String networkB = policy.attachNetwork(SESSION_B);

        // 실제 프리뷰 컨테이너와 같은 방식으로(DockerContainerService#createAndStartContainer 참고)
        // 각자 자기 세션 네트워크에만 붙는 헬퍼를 띄운다 — qeploy-egress 별칭이 그 네트워크 안에서
        // 프록시 IP 로 풀리는 것도 실제 프리뷰와 동일하다.
        createHelper(HELPER_A, networkA);
        createHelper(HELPER_B, networkB);

        // A: 아무것도 보내지 않는 연결을 상한만큼 연다. "sleep N | nc -w N" 은 stdin 이 끝까지
        // 비지도 닫히지도 않게 해서, 실측 공격(연결만 열고 침묵)과 같은 모양을 만든다 — stdin 을
        // /dev/null 로 두면 nc 가 즉시 반쪽 닫음(EOF)을 보내 tinyproxy 가 바로 그 연결을
        // 회수해버려 상한을 채우지 못한다.
        for (int i = 0; i < PER_SESSION_MAX_CLIENTS; i++) {
            execDetached(HELPER_A, "sleep 20 | nc -w 20 qeploy-egress 8888");
        }
        // tinyproxy 의 accept 루프가 위 연결들을 실제로 스레드에 배정할 시간을 준다 — 그러지
        // 않으면 바로 아래에서 보내는 "상한 초과" 요청이 아직 비어 있는 슬롯을 받아버려 거짓
        // 성공(상한이 안 걸린 것처럼 보임)이 날 수 있다.
        Thread.sleep(1500);

        // A 자신: 상한을 넘긴 요청은 응답이 오지 않는다 — 세션 자신에게도 상한이 실제로 걸린다는
        // 확인(완료 기준 "A 자신은 상한 초과분이 거부된다"). nc 의 -w 는 "연결과 마지막 읽기" 를
        // 함께 묶는 타임아웃이라 stdin 이 파이프로 일찍 닫히는 모양에서는 스스로 안 끝날 때가
        // 있었다(실측) — 그래서 바깥을 busybox timeout 으로 한 번 더 감싸 확실히 끝낸다. 응답이
        // 없으면 표준출력은 빈 문자열이다.
        String overflowResponse = execCapture(HELPER_A, connectThroughNc(3), 8);
        assertThat(overflowResponse)
                .as("A 의 상한 초과 요청은 tinyproxy 의 accept 스레드가 전부 유휴 연결에 묶여 있어"
                        + " 응답을 받지 못해야 한다")
                .isEmpty();

        // B: 별도 프로세스라 A 의 상태와 무관하게 즉시(아웃바운드 연결을 열기도 전에) 403 을
        // 돌려준다 — #466 이 고치는 가용성 격리 그 자체.
        String viaBResponse = execCapture(HELPER_B, connectThroughNc(5), 10);
        assertThat(viaBResponse)
                .as("B 는 A 의 연결 점유와 무관하게 자기 프로세스에서 즉시 응답해야 한다")
                .contains("403");
    }

    private static String connectThroughNc(int timeoutSeconds) {
        return "printf 'CONNECT " + DISALLOWED_TARGET + " HTTP/1.1\\r\\nHost: " + DISALLOWED_TARGET
                + "\\r\\n\\r\\n' | timeout " + timeoutSeconds + " nc -w " + timeoutSeconds
                + " qeploy-egress 8888";
    }

    /** 세션 네트워크에만 붙는 대기 컨테이너 — 그 안에서 exec 로 프록시에 말을 건다. */
    private void createHelper(String name, String network) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // 처음 실행이면 당연히 없다.
        }
        var created = dockerClient.createContainerCmd(HELPER_IMAGE)
                .withName(name)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                .withCmd("sleep", "infinity")
                .exec();
        dockerClient.startContainerCmd(created.getId()).exec();
    }

    /** 끝나기를 기다리지 않는다 — 유휴 연결을 그대로 들고 있어야 하는 백그라운드 작업에 쓴다. */
    private void execDetached(String containerId, String command) {
        var created = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", command)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .exec();
        dockerClient.execStartCmd(created.getId()).withDetach(true)
                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>());
    }

    /** 끝날 때까지 기다리고 표준출력을 모아 돌려준다. */
    private String execCapture(String containerId, String command, int timeoutSeconds) throws InterruptedException {
        var created = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        dockerClient.execStartCmd(created.getId())
                .withDetach(false)
                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDOUT) {
                            stdout.writeBytes(frame.getPayload());
                        }
                    }
                })
                .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void bestEffort(ThrowingAction action) {
        try {
            action.run();
        } catch (NotFoundException e) {
            // 이미 없음 — 뒷정리 대상이 원래 없던 경우라 정상이다.
        } catch (Exception e) {
            // 테스트 뒷정리 실패는 테스트 결과에 영향을 주지 않는다. 남은 컨테이너/네트워크는
            // 다음 실행의 @BeforeEach/@AfterEach 가 같은 이름으로 다시 정리를 시도한다.
        }
    }
}
