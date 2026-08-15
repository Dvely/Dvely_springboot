package com.example.dvely.auth.domain.model;

import com.example.dvely.auth.domain.value.GithubId;

import java.time.LocalDateTime;

public class User {

    // GitHub App User Token 의 수명. GitHub 이 정한 값이라 우리가 고를 수 없다.
    private static final long ACCESS_TOKEN_HOURS = 8L;
    private static final long REFRESH_TOKEN_DAYS = 180L;

    private Long id;
    private GithubId githubId;
    private String username;
    private String avatarUrl;
    private Long githubInstallationId;
    // GitHub App User Token (8시간 만료, DB 암호화 저장)
    private String githubUserAccessToken;
    private String githubUserRefreshToken;
    private LocalDateTime userAccessTokenExpiresAt;

    public User(GithubId githubId, String username, String avatarUrl) {
        this.githubId = githubId;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public User(Long id, GithubId githubId, String username, String avatarUrl,
                Long githubInstallationId, String githubUserAccessToken,
                String githubUserRefreshToken, LocalDateTime userAccessTokenExpiresAt) {
        this.id = id;
        this.githubId = githubId;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.githubInstallationId = githubInstallationId;
        this.githubUserAccessToken = githubUserAccessToken;
        this.githubUserRefreshToken = githubUserRefreshToken;
        this.userAccessTokenExpiresAt = userAccessTokenExpiresAt;
    }

    public Long getId() { return id; }
    public GithubId getGithubId() { return githubId; }
    public String getUsername() { return username; }
    public String getAvatarUrl() { return avatarUrl; }
    public Long getGithubInstallationId() { return githubInstallationId; }
    public String getGithubUserAccessToken() { return githubUserAccessToken; }
    public String getGithubUserRefreshToken() { return githubUserRefreshToken; }
    public LocalDateTime getUserAccessTokenExpiresAt() { return userAccessTokenExpiresAt; }

    public boolean hasGithubAppInstalled() {
        return githubInstallationId != null;
    }

    // 만료 5분 전부터 만료로 간주해 미리 갱신
    public boolean isUserAccessTokenExpired() {
        return userAccessTokenExpiresAt == null ||
               LocalDateTime.now().plusMinutes(5).isAfter(userAccessTokenExpiresAt);
    }

    /**
     * 리프레시 토큰 만료 시각. GitHub은 이 값을 따로 주지 않으므로 액세스 토큰 만료 시각에서
     * 발급 시각을 되짚어 계산한다. 갱신 때마다 리프레시 토큰도 함께 회전하므로 180일은 마지막
     * 갱신 시점부터 다시 센다.
     */
    public LocalDateTime getRefreshTokenExpiresAt() {
        return userAccessTokenExpiresAt == null ? null
                : userAccessTokenExpiresAt.minusHours(ACCESS_TOKEN_HOURS).plusDays(REFRESH_TOKEN_DAYS);
    }

    /**
     * 유저가 직접 재인증해야 하는 상태인지. 액세스 토큰이 만료된 것만으로는 해당하지 않는다 —
     * 리프레시 토큰이 살아 있으면 GitHub을 실제로 호출하는 경로들이 refreshGithubUserToken 으로
     * 조용히 재발급하므로 유저가 할 일이 없다. 재인증이 필요한 건 그 자동 갱신이 불가능할
     * 때뿐이다: 리프레시 토큰이 없거나(미연동, 또는 bad_refresh_token 으로 clearGithubAppToken
     * 이 비운 뒤) 180일 수명이 지난 경우.
     */
    public boolean needsGithubAppReauthorization() {
        if (githubUserRefreshToken == null) {
            return true;
        }
        LocalDateTime refreshExpiresAt = getRefreshTokenExpiresAt();
        return refreshExpiresAt == null || LocalDateTime.now().isAfter(refreshExpiresAt);
    }

    public void updateProfile(String username, String avatarUrl) {
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public void updateInstallationId(Long installationId) {
        this.githubInstallationId = installationId;
    }

    public void updateUserToken(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        this.githubUserAccessToken = accessToken;
        this.githubUserRefreshToken = refreshToken;
        this.userAccessTokenExpiresAt = expiresAt;
    }

    public void clearGithubAppToken() {
        this.githubUserAccessToken = null;
        this.githubUserRefreshToken = null;
        this.userAccessTokenExpiresAt = null;
    }

    public void disconnectGithubApp() {
        this.githubInstallationId = null;
        clearGithubAppToken();
    }
}
