package com.example.dvely.common.exception;

/**
 * GitHub App(설치 토큰)이 대상 저장소에 접근할 권한이 없어 저장소 작업이 403 으로 거부됨
 * ("Resource not accessible by integration"). App 이 그 레포에 설치/허용되지 않았을 때 발생한다.
 * 일반 실패(CONFLICT)와 달리 사용자가 GitHub App 설정에서 저장소 접근을 허용하면 풀리므로, FE 가
 * 그 안내(재인증/권한 부여)로 유도할 수 있게 별도 errorCode 로 내려준다.
 */
public class GithubRepositoryAccessDeniedException extends RuntimeException {
    public GithubRepositoryAccessDeniedException(String message) {
        super(message);
    }
}
