package com.example.dvely.provisioning.domain.value;

/**
 * 조회할 서버 로그 종류. 실행 형태(NATIVE/DOCKER)와 무관하게 사용자가 고르는 논리적 소스다 —
 * 서비스가 형태별 실제 셸 명령으로 옮긴다.
 */
public enum ServerLogSource {
    /** 앱 로그(백엔드 java/node 또는 컨테이너). "내 앱이 뭘 찍었나". */
    APP,
    /** 부트스트랩 로그(cloud-init). "앱이 왜 안 떴나" 진단 1순위(dnf·docker load·compose up 실패). */
    BOOT,
    /** Caddy(HTTPS 종단) 로그. 도메인·인증서 문제 진단. */
    CADDY
}
