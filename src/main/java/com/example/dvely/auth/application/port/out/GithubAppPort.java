package com.example.dvely.auth.application.port.out;

public interface GithubAppPort {

    /**
     * 이 User Token 의 주인이 이 App 의 해당 설치에 접근할 수 있는지 확인한다(GET /user/installations).
     * 콜백이 준 installation_id 를 저장하기 전에 반드시 거친다(#504). GitHub 호출 실패는 던진다 —
     * false 로 삼키면 "남의 설치"라는 틀린 안내가 나간다.
     */
    boolean canAccessInstallation(String userToken, long installationId);

    /**
     * GitHub App 설치 페이지 URL 반환
     * state 는 1회용 연동 요청 값(GithubAppStateManager)
     */
    String getInstallationUrl(String state);

    /**
     * GitHub App User Token 재인증 URL 반환
     * 이미 설치된 App의 권한은 유지하면서 만료된 User Token만 재발급받는 경로
     */
    String getReauthorizeUrl(Long installationId, String state);

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
