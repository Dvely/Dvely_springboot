package com.example.dvely.common.exception;

/**
 * GitHub App 은 설치돼 있는데 사용자 토큰을 쓸 수 없는 상태 — 토큰이 없거나 GitHub 이 리프레시를
 * 거부했다(#516). 필요한 것은 설치가 아니라 <b>재인증</b>이다.
 *
 * <p>{@link GithubAppNotInstalledException} 과 같은 이유로 전용 코드를 둔다. 클라이언트는 코드로
 * 진입점을 고르는데, 예전에는 이 상태가 범용 {@code FORBIDDEN}(또는 경로에 따라 409·400)으로
 * 나가 재인증 모달이 뜨지 않았고, 문구는 "다시 설치해 주세요"라고 틀리게 안내했다.</p>
 */
public class GithubAppReauthorizationRequiredException extends RuntimeException {

    public static final String DEFAULT_MESSAGE = "GitHub 인증이 만료됐습니다. GitHub App 권한을 다시 인증해 주세요.";

    public GithubAppReauthorizationRequiredException() {
        super(DEFAULT_MESSAGE);
    }
}
