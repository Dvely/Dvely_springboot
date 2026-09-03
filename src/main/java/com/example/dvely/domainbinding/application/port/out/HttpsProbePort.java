package com.example.dvely.domainbinding.application.port.out;

/**
 * 도메인이 실제로 HTTPS 로 서빙되는지 확인하는 포트. 호스팅 상태값이 실상과 어긋나는 두 경로를 검증에서
 * 보정하는 데 쓴다: (1) AWS 백엔드는 Caddy on-demand 로 HTTPS 를 종단하지만 설치가 best-effort 라
 * "붙었으면 HTTPS"로 단정 못 함. (2) GitHub Pages 는 Cloudflare 프록시 도메인의 인증서를 GitHub 이
 * 검증 못 해 상태가 영원히 PENDING 이지만 엣지 인증서로 실제 https 는 됨. 두 경우 모두 이 포트로 실제
 * https 응답(유효 인증서)을 확인해 {@code httpsEnforced} 를 실상대로 채운다.
 */
public interface HttpsProbePort {

    /**
     * {@code https://hostname/} 가 유효한 인증서로 응답하면 true. TLS 핸드셰이크 실패·타임아웃·연결
     * 거부(아직 DNS 미연결, Caddy 미기동, 인증서 미발급 등)면 false. 예외를 던지지 않는다(검증 워커가
     * 반복 호출하므로 조용히 false).
     */
    boolean isHttpsServing(String hostname);
}
