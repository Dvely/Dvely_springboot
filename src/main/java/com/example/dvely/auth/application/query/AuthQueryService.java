package com.example.dvely.auth.application.query;

import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthQueryService {

    private final GithubOAuthPort githubOAuthPort;

    public String getGithubLoginUrl() {
        return githubOAuthPort.getAuthorizeUrl();
    }
}
