package com.example.dvely.auth.application.port.out;

import java.util.Optional;

public interface GithubAppPort {

    /**
     * OAuth 유저 토큰으로 해당 유저의 GitHub App 설치 ID 조회
     * GitHub App이 설치되지 않은 경우 Optional.empty() 반환
     */
    Optional<Long> findInstallationId(String oauthToken);

    /**
     * Installation ID로 Installation Access Token 발급
     * 이 토큰으로 레포 접근 등 고급 API 작업 수행
     * 유효시간: 1시간
     */
    String getInstallationToken(long installationId);

    /**
     * GitHub App 설치 페이지 URL 반환
     * state에 서비스 JWT를 담아 콜백에서 유저를 식별
     *
     * @param state 콜백에서 유저 식별에 사용할 값 (서비스 JWT)
     */
    String getInstallationUrl(String state);
}
