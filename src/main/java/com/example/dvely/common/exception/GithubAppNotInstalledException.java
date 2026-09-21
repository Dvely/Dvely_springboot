package com.example.dvely.common.exception;

/**
 * GitHub App 이 설치되지 않은 사용자가 설치를 전제한 경로를 탔을 때.
 *
 * <p><b>재인증과 다르다.</b> 재인증은 권한은 그대로고 토큰만 다시 받으면 되는 상태이고, 이것은
 * 아직 권한을 준 적이 없는 상태다. 화면이 가야 할 곳도 다르다 — 설치 화면과 재인증 화면이다.</p>
 *
 * <p>전용 예외를 두는 이유는 <b>코드</b>다. 예전에는 {@link ForbiddenException} 으로 던져
 * {@code FORBIDDEN} 으로 나갔는데, 클라이언트는 코드로 분기하므로 설치 안내에 닿지 못하고
 * 재인증을 다시 시도했다 — 사용자가 같은 403 을 반복해서 맞는 고리가 됐다(#367).</p>
 */
public class GithubAppNotInstalledException extends RuntimeException {

    public GithubAppNotInstalledException(String message) {
        super(message);
    }
}
