package com.example.dvely.auth.domain.model;

import com.example.dvely.auth.domain.value.GithubId;

public class User {

    private Long id;
    private GithubId githubId;
    private String username;
    // GitHub App installation ID - 설치된 경우에만 존재 (nullable)
    private Long githubInstallationId;

    public User(GithubId githubId, String username) {
        this.githubId = githubId;
        this.username = username;
    }

    // Infrastructure(어댑터)에서 DB 조회 후 복원할 때 사용
    public User(Long id, GithubId githubId, String username, Long githubInstallationId) {
        this.id = id;
        this.githubId = githubId;
        this.username = username;
        this.githubInstallationId = githubInstallationId;
    }

    public Long getId() {
        return id;
    }

    public GithubId getGithubId() {
        return githubId;
    }

    public String getUsername() {
        return username;
    }

    public Long getGithubInstallationId() {
        return githubInstallationId;
    }

    public boolean hasGithubAppInstalled() {
        return githubInstallationId != null;
    }

    public void updateUsername(String username) {
        this.username = username;
    }

    public void updateInstallationId(Long installationId) {
        this.githubInstallationId = installationId;
    }
}
