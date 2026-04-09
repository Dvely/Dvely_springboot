package com.example.dvely.auth.presentation;

import com.example.dvely.auth.application.facade.AuthFacade;
import com.example.dvely.auth.presentation.dto.AuthTokenResponse;
import com.example.dvely.auth.presentation.dto.GithubUrlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;

    @GetMapping("/github/url")
    public GithubUrlResponse getGithubUrl() {
        return new GithubUrlResponse(authFacade.getGithubLoginUrl());
    }

    @GetMapping("/github/callback")
    public AuthTokenResponse callback(@RequestParam String code) {
        var result = authFacade.loginWithGithub(code);
        return new AuthTokenResponse(result.accessToken());
    }
}
