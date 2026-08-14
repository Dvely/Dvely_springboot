package com.example.dvely.preview.infrastructure.health;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 프리뷰가 실제로 동작할 수 있는 상태인지 한 번 점검해 로그로 남긴다.
 *
 * <p>프리뷰의 두 가지 실패는 모두 기동 시점에는 조용하다. Docker 는 첫 컨테이너 요청 때까지
 * 연결하지 않으므로 없어도 앱이 정상 기동하고, 게이트웨이 오리진이 localhost 여도 API 는 200 을
 * 돌려준다 — 잘못된 주소가 담겨 있을 뿐이다. 둘 다 사용자가 프리뷰를 누른 다음에야 드러나고,
 * 그때는 서버에 직접 붙어봐야 원인을 알 수 있었다. 이 점검이 그 확인을 기동 로그로 앞당긴다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewEnvironmentHealthLogger {

    private final DockerContainerService dockerService;
    private final PreviewGatewayUrlResolver gatewayUrlResolver;

    @EventListener(ApplicationReadyEvent.class)
    public void reportPreviewEnvironment() {
        if (dockerService.ping()) {
            log.info("[PreviewEnv] Docker 연결 정상 · 프리뷰 기준 오리진 = {}", gatewayUrlResolver.baseUrl());
            return;
        }
        log.warn("""
                [PreviewEnv] Docker 데몬에 연결하지 못했습니다. 프리뷰 컨테이너를 띄우는 모든 기능이
                실패합니다(프로젝트 프리뷰 API 는 503, Agent CODE 스텝은 작업 실패).
                  설치:   sudo apt-get install -y docker.io && sudo systemctl enable --now docker
                  권한:   sudo usermod -aG docker $(whoami)   # 적용하려면 앱 프로세스를 재시작
                  확인:   docker ps""");
    }
}
