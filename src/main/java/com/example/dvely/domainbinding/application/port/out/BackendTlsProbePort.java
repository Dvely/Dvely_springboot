package com.example.dvely.domainbinding.application.port.out;

/**
 * 도메인이 실제로 HTTPS 로 서빙되는지 확인하는 포트. AWS 백엔드는 인스턴스의 Caddy 가 on-demand TLS 로
 * HTTPS 를 종단하는데, Caddy 설치는 best-effort(user-data 실패 가능)라 "붙었으면 HTTPS 다"라고 단정할 수
 * 없다. 그래서 도메인 검증 시 이 포트로 실제 https 응답(유효 인증서)을 확인해 {@code httpsEnforced} 를
 * 실상대로 채운다 — 승인 요약·화면이 하드코딩 문자열이 아니라 이 실측값에서 파생되게 한다.
 */
public interface BackendTlsProbePort {

    /**
     * {@code https://hostname/} 가 유효한 인증서로 응답하면 true. TLS 핸드셰이크 실패·타임아웃·연결
     * 거부(아직 DNS 미연결, Caddy 미기동, 인증서 미발급 등)면 false. 예외를 던지지 않는다(검증 워커가
     * 반복 호출하므로 조용히 false).
     */
    boolean isHttpsServing(String hostname);
}
