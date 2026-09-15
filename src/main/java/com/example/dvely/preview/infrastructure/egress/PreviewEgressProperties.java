package com.example.dvely.preview.infrastructure.egress;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프리뷰 컨테이너의 바깥 통신을 허용목록으로 좁히는 설정 (#332 4단계).
 *
 * <p><b>기본은 꺼짐이다.</b> 허용목록이 좁으면 빌드가 깨지는데, 그 실패는 사용자 프로젝트마다
 * 다르게 나타나 원인을 가리기 어렵다. 실제 프로젝트(정적·Node·JAVA_FULLSTACK)로 태워 목록을
 * 확인한 뒤 켠다.</p>
 *
 * @param enabled       끄면 프리뷰는 지금처럼 공유 브리지에 붙고 바깥이 열려 있다
 * @param proxyImage    프록시 사이드카 이미지. tinyproxy 를 여기에 설치해 띄운다
 * @param proxyName     프록시 컨테이너 이름. <b>모든 프리뷰가 하나를 공유한다</b>
 * @param proxyAlias    프리뷰가 프록시를 부르는 DNS 이름. 고정이라 프록시 URL 도 고정이다
 * @param proxyPort     tinyproxy 수신 포트
 * @param networkPrefix 프리뷰마다 만드는 전용 네트워크 이름의 접두
 * @param allowedHosts  나갈 수 있는 호스트. 통째로 일치해야 하며 하위 도메인은 열리지 않는다
 */
@ConfigurationProperties(prefix = "qeploy.preview.egress")
public record PreviewEgressProperties(
        boolean enabled,
        String proxyImage,
        String proxyName,
        String proxyAlias,
        int proxyPort,
        String networkPrefix,
        List<String> allowedHosts
) {

    public String proxyUrl() {
        return "http://" + proxyAlias + ":" + proxyPort;
    }

    /** 이 프리뷰 세션 전용 네트워크 이름. 세션마다 달라야 서로 라우팅되지 않는다. */
    public String networkName(String previewSessionId) {
        return networkPrefix + previewSessionId;
    }
}
