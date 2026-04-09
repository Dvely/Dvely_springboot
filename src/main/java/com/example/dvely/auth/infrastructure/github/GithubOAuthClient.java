package com.example.dvely.auth.infrastructure.github;

import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import org.springframework.stereotype.Component;

@Component
public class GithubOAuthClient implements GithubOAuthPort {

    @Override
    public String getAuthorizeUrl() {
        return "https://github.com/login/oauth/authorize?client_id=YOUR_CLIENT_ID";
    }

    @Override
    public String getAccessToken(String code) {
        // TODO: RestTemplate / WebClient로 실제 GitHub API 호출
        return "mock_access_token";
    }
}
