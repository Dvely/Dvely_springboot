package com.example.dvely.auth.domain.model;

import com.example.dvely.auth.domain.value.GithubId;

import java.time.LocalDateTime;

public class User {

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
}
