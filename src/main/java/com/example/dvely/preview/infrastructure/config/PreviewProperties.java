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
     * 게이트웨이가 소유권 쿠키를 요구할지 (Issue #77 G2).
     *
     * <p>기본값이 {@code true}인 것은 의도적이다 — 끄면 "URL을 아는 자 = 접근 허가" 모델로 되돌아간다.
     * FE가 접근 발급 호출을 아직 배포하지 못한 환경에서만 임시로 끄고, 끈 동안에는 그 갭이 그대로
     * 열려 있다는 것을 알고 있어야 한다.</p>
     */
    private boolean requireAccessCookie = true;
}
