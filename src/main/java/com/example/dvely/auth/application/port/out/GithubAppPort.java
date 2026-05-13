package com.example.dvely.auth.application.port.out;

import java.util.Optional;

public interface GithubAppPort {

    /**
     * OAuth 유저 토큰으로 해당 유저의 GitHub App 설치 ID 조회
     * GitHub App이 설치되지 않은 경우 Optional.empty() 반환
     */
    Optional<Long> findInstallationId(String oauthToken);

    /**
     * GitHub App 설치 페이지 URL 반환
     * state에 서비스 JWT를 담아 콜백에서 유저를 식별
     */
    String getInstallationUrl(String state);

    /**
     * GitHub App User Token 발급
     * 설치 콜백에서 받은 code로 access_token + refresh_token 교환
     * access_token 유효시간: 8시간 / refresh_token 유효시간: 6개월
     */
    GithubUserTokenInfo getUserToken(String code);

    /**
     * GitHub App User Token 갱신
     * refresh_token으로 새 access_token + refresh_token 발급
     */
    GithubUserTokenInfo refreshUserToken(String refreshToken);

    /**
     * GitHub App Installation Access Token 발급 (server-to-server)
     * User Token으로 불가능한 작업(워크플로우 파일 생성 등)에 사용
     * 유효시간: 1시간
     */
    String getInstallationToken(Long installationId);

    record GithubUserTokenInfo(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            long refreshTokenExpiresInSeconds
    ) {}
}
