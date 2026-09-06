package com.example.dvely.provisioning.application.port.out;

import java.util.List;

/**
 * 프로젝트의 프론트엔드 오리진(백엔드가 CORS 로 허용할 대상)을 provisioning 에 넘기는 포트. 배포 시
 * 백엔드 env(QEPLOY_ALLOWED_ORIGINS)로 주입돼, 사용자 백엔드가 그 프론트에서 오는 요청을 CORS 로 받는다.
 * 구현은 인프라 계층에서 project·domainbinding 을 읽어 다리를 놓는다.
 */
public interface FrontendOriginPort {

    /** 이 프로젝트의 프론트 배포 URL·프론트 도메인에서 CORS 허용 오리진(scheme://host) 목록. 없으면 빈 목록. */
    List<String> resolveAllowedOrigins(Long projectId);
}
