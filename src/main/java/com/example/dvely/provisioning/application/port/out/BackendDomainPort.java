package com.example.dvely.provisioning.application.port.out;

import java.util.Optional;

/**
 * 프로젝트 백엔드에 연결된 도메인을 provisioning 에 넘기는 포트. 서버 응답(ServerResponse.domainUrl)이
 * 원시 EIP 주소 대신 사용자가 붙인 도메인을 보여줄 때 쓴다. 구현은 인프라 계층에서 domainbinding 을
 * 읽어 다리를 놓는다 — provisioning 응용 계층은 이 포트에만 의존한다(도메인 경계 유지, BackendAddressPort
 * 의 반대 방향).
 */
public interface BackendDomainPort {

    /** 이 프로젝트의 백엔드(AWS)에 연결된 CONNECTED 도메인 호스트네임. 없으면 empty. */
    Optional<String> resolveConnectedBackendDomain(Long projectId);
}
