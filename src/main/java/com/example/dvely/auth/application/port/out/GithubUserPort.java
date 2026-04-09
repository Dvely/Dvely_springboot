package com.example.dvely.auth.application.port.out;

public interface GithubUserPort {
    GithubUserInfo getUser(String accessToken);

    record GithubUserInfo(String id, String login) {}
}
