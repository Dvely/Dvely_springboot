package com.example.dvely.preview.infrastructure.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "qeploy.preview")
public class PreviewProperties {

    private String gatewayBaseUrl = "http://localhost:8080";
    private Duration ttl = Duration.ofMinutes(30);

    /**
     * 저장소 연결 승인이 열렸을 때 그 태스크의 세션에 주는 만료 유예.
     *
     * <p>기본 TTL 은 사람이 화면을 보고 있다는 전제로 잡은 값이고, 접근할 때마다
     * {@code touch} 로 갱신된다. 저장소 연결 승인은 그 전제가 깨지는 유일한 자리다 — 사용자는
     * 프리뷰를 열어보지 않고 채팅의 승인 카드만 보고 결정할 수 있고, 그동안 세션은 아무에게도
     * 접근되지 않은 채 30분에 회수된다. 그런데 이 승인의 대상 작업물은 아직 GitHub 에 없고
     * 컨테이너 안에만 있으므로, 회수되는 순간 승인해도 올릴 것이 없다.</p>
     *
     * <p>무한정 붙들지 않고 유한한 값인 것도 의도다. 답이 오지 않는 승인이 컨테이너를 영원히
     * 붙들면 디스크가 찬다. 이 시간이 지나 회수되면 승인은
     * {@code ApprovalCommandService} 가 사용자에게 사유를 말하며 닫는다.</p>
     */
    private Duration bindingApprovalHold = Duration.ofHours(6);

    /**
     * 게이트웨이가 소유권 쿠키를 요구할지 (Issue #77 G2).
     *
     * <p>기본값이 {@code true}인 것은 의도적이다 — 끄면 "URL을 아는 자 = 접근 허가" 모델로 되돌아간다.
     * FE가 접근 발급 호출을 아직 배포하지 못한 환경에서만 임시로 끄고, 끈 동안에는 그 갭이 그대로
     * 열려 있다는 것을 알고 있어야 한다.</p>
     */
    private boolean requireAccessCookie = true;
}
