package com.example.dvely.deployment.application.port.out;

/**
 * 프론트엔드 정적 산출물을 사용자 클라우드의 정적 웹호스팅으로 발행하는 포트. GitHub Pages 경로와 달리
 * 서버측에서 직접 빌드해 올린다(BYOC assume-role, 백엔드 배포와 같은 모델). 구현 어댑터는 provisioning
 * 도메인에 있어 도커 빌드 샌드박스·소스 clone·S3 배관을 재사용한다 — 이 인터페이스는 deployment 가
 * 그 세부에 얽히지 않도록 두는 경계다.
 */
public interface FrontendStaticHostingPort {

    /**
     * 프론트 소스를 빌드해 프로젝트의 S3 정적 웹호스팅 버킷에 발행하고 공개 접근 URL 을 반환한다.
     * 프로젝트의 클라우드 연결(AWS)은 구현체가 내부에서 해석한다. 실패는 런타임 예외로 전달돼
     * 배포 워커의 재시도 기계로 들어간다.
     *
     * @param request 발행 대상(프로젝트·소유자·소스 저장소·빌드 기준 ref)
     * @return 발행된 사이트의 접근 URL(예: S3 website 엔드포인트)
     */
    String publishToS3(PublishRequest request);

    /**
     * @param projectId   대상 프로젝트
     * @param ownerUserId 소유자(소스 clone 토큰·클라우드 연결 소유자 확인용)
     * @param sourceRepo  {@code owner/repo} 형식 소스 저장소
     * @param checkoutRef 빌드할 git ref. 기본 브랜치(예: main)면 clone 그대로, 아니면 그 태그를
     *                    받아 체크아웃한다.
     */
    record PublishRequest(Long projectId, Long ownerUserId, String sourceRepo, String checkoutRef) {}
}
