package com.example.dvely.project.application.port.out;

/**
 * 프로젝트가 사라질 때 남는 클라우드 자원을 정리하는 포트. 지금은 프론트 S3 정적 사이트 버킷 —
 * 프론트를 S3 로 배포했던 프로젝트를 지우면 공개 버킷이 사용자 계정에 고아로 남아(BYOC 신뢰 직격),
 * 이를 자동 정리한다. 구현은 provisioning 도메인(클라우드 연결·S3 배관 보유).
 */
public interface ProjectCloudCleanupPort {

    /**
     * 프로젝트의 프론트 S3 정적 사이트 버킷을 정리한다(있으면 삭제, 없거나 연결이 없으면 no-op).
     * <b>best-effort</b> — 절대 예외를 던지지 않는다(정리 실패가 삭제 흐름을 되돌리면 안 된다).
     */
    void cleanupFrontendS3(Long projectId, Long ownerUserId);
}
