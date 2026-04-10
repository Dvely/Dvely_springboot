package com.example.dvely.auth.infrastructure.external.github;

import com.example.dvely.auth.application.port.out.GithubUserPort;
import org.springframework.stereotype.Component;

@Component
public class GithubUserClient implements GithubUserPort {

    @Override
    public GithubUserInfo getUser(String accessToken) {
        // TODO: RestTemplate / WebClient로 실제 GitHub API 호출
        return new GithubUserInfo("12345", "test-user");
    }
}
