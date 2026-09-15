package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;

/**
 * 게이트웨이와 레디니스 프로브가 컨테이너를 가리킬 때 쓰는 주소를 한 곳에서 만든다.
 *
 * <p>둘이 각자 {@code ip + ":" + 3000} 을 조립하면 한쪽만 바뀌었을 때 502 로만 드러난다 — 프리뷰가
 * 깨진 원인을 찾기 가장 어려운 형태다(#274·#278·#281 이 그 자리에 쌓여 있다).</p>
 */
final class PreviewTarget {

    private PreviewTarget() {
    }

    /**
     * @param containerIp 컨테이너 IP. 포트가 이미 붙어 있으면 그대로 쓴다 — 운영에서는 IP 만
     *                    오지만, 테스트는 임의 포트의 로컬 서버를 세우고 {@code "127.0.0.1:1234"}
     *                    형태로 넘긴다. 프리뷰가 기본 포트가 아닌 곳에서 서빙하게 되는 날에도
     *                    이 한 줄이 그대로 답이 된다.
     */
    static String authority(String containerIp) {
        if (containerIp == null || containerIp.isBlank()) {
            throw new IllegalStateException("프리뷰 컨테이너 주소가 없습니다");
        }
        return containerIp.contains(":")
                ? containerIp
                : containerIp + ":" + DockerContainerService.CONTAINER_PORT;
    }

    /** {@code http://<authority>/} 까지 만든 접두. 뒤에 경로를 붙여 쓴다. */
    static String baseUrl(String containerIp) {
        return "http://" + authority(containerIp) + "/";
    }
}
