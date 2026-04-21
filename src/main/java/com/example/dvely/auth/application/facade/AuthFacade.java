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

    public String getGithubAppInstallUrl(String userToken) {
        return authQueryService.getGithubAppInstallUrl(userToken);
    }

    public TokenResult loginWithGithub(String code) {
        return authCommandService.loginWithGithub(new GithubLoginCommand(code));
    }

    public void linkGithubApp(Long userId, Long installationId, String code) {
        authCommandService.linkGithubApp(userId, installationId, code);
    }

    public TokenResult refresh(String refreshToken) {
        return authCommandService.refresh(refreshToken);
    }

    public void logout(Long userId, String accessToken) {
        authCommandService.logout(userId, accessToken);
    }
}
