package com.example.dvely.domainbinding.application.port.out;

import java.util.Optional;

/**
 * 프로젝트의 실행 중 백엔드 서버 공개 주소를 도메인바인딩에 넘기는 포트. AWS 호스팅 어댑터가 도메인이
 * 가리킬 대상(EIP)을 A 레코드로 걸 때 쓴다. 구현은 인프라 계층에서 provisioning 을 읽어 다리를 놓는다 —
 * 도메인바인딩 응용 계층은 이 포트에만 의존한다(도메인 경계 유지).
 */
public interface BackendAddressPort {

    /** 이 프로젝트의 RUNNING 백엔드 서버 공개 IP. 없으면 empty(서버 미배포·미기동). */
    Optional<String> resolveRunningBackendIp(Long projectId);
}
