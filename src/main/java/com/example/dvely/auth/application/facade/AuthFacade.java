package com.example.dvely.auth.application.facade;

import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.application.command.dto.GithubLoginCommand;
import com.example.dvely.auth.application.command.dto.TokenResult;
import com.example.dvely.auth.application.query.AuthQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthFacade {

    private final AuthCommandService authCommandService;
    private final AuthQueryService authQueryService;

    public String getGithubLoginUrl() {
        return authQueryService.getGithubLoginUrl();
    }

    public TokenResult loginWithGithub(String code) {
        return authCommandService.loginWithGithub(new GithubLoginCommand(code));
    }
}
