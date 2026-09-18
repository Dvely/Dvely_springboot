package com.example.dvely.preview.infrastructure.egress;

import com.example.dvely.agent.infrastructure.docker.DockerClients;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Network;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기본 동작 — 모든 프리뷰가 공유 브리지 하나에 붙고, 바깥은 열려 있다.
 *
 * <p>컨테이너 사이는 {@code enable_icc=false} 가 막는다. egress 를 거르지 않는 대신 이 격리를
 * 유지하는 쪽이고, 켜기 전까지의 상태다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "qeploy.preview.egress", name = "enabled", havingValue = "false", matchIfMissing = true)
public class SharedBridgePreviewNetworkPolicy implements PreviewNetworkPolicy {

    public static final String NETWORK_NAME = "qeploy-preview";

    // DockerClient 는 스프링 빈이 아니다(없는 환경에서 컨텍스트가 통째로 죽는다) — 직접 만든다.
    private final DockerClient dockerClient = DockerClients.createLocal();

    @Override
    public String attachNetwork(String previewSessionId) {
        ensureNetwork();
        return NETWORK_NAME;
    }

    @Override
    public List<String> environment() {
        return List.of();
    }

    @Override
    public void release(String previewSessionId) {
        // 공유 네트워크라 세션마다 되돌릴 것이 없다.
    }

    /**
     * 없으면 만들고, 있는데 격리 옵션이 빠져 있으면 경고한다.
     *
     * <p>고치지 않는다 — 살아 있는 네트워크에 이미 컨테이너가 붙어 있을 수 있고, 그 아래에서
     * 다시 만드는 편이 경고보다 나쁘다. 막으려는 실패는 "정책이 기대는 성질을 네트워크가 조용히
     * 잃은 상태" 다.</p>
     */
    private void ensureNetwork() {
        // 이름 필터는 부분 일치라, 남아 있던 "qeploy-preview-old" 를 진짜로 오인하지 않도록
        // 정확히 같은 이름만 본다.
        boolean exists = dockerClient.listNetworksCmd().withNameFilter(NETWORK_NAME).exec().stream()
                .anyMatch(network -> NETWORK_NAME.equals(network.getName()));
        if (exists) {
            verifyIccDisabled();
            return;
        }
        try {
            dockerClient.createNetworkCmd()
                    .withName(NETWORK_NAME)
                    .withDriver("bridge")
                    .withOptions(Map.of("com.docker.network.bridge.enable_icc", "false"))
                    .exec();
            log.info("Docker preview 네트워크 생성: name={}", NETWORK_NAME);
        } catch (ConflictException e) {
            log.debug("Docker preview 네트워크가 동시 생성 레이스로 이미 존재함: name={}", NETWORK_NAME);
        }
    }

    private void verifyIccDisabled() {
        try {
            // list 응답은 Options 를 온전히 싣지 않는다 — 그래서 확인하려면 inspect 를 따로 부른다.
            Network network = dockerClient.inspectNetworkCmd().withNetworkId(NETWORK_NAME).exec();
            Map<String, String> options = network.getOptions();
            if (options == null || !"false".equals(options.get("com.docker.network.bridge.enable_icc"))) {
                log.warn("Docker preview 네트워크에 enable_icc=false 가 없습니다 — 프리뷰 간 격리가 실제로는 걸려 있지 않습니다. name={}",
                        NETWORK_NAME);
            }
        } catch (RuntimeException e) {
            log.warn("Docker preview 네트워크 격리 옵션을 확인하지 못했습니다: name={} 사유={}", NETWORK_NAME, e.toString());
        }
    }
}
