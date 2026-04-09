package com.example.dvely.auth.domain.model;

import com.example.dvely.auth.domain.value.GithubId;

public class User {

    private Long id;
    private GithubId githubId;
    private String username;

    public User(GithubId githubId, String username) {
        this.githubId = githubId;
        this.username = username;
    }

    // Infrastructure(어댑터)에서 DB 조회 후 복원할 때 사용
    public User(Long id, GithubId githubId, String username) {
        this.id = id;
        this.githubId = githubId;
        this.username = username;
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

    public void updateUsername(String username) {
        this.username = username;
    }
}
