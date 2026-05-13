package com.example.dvely.auth.application.port.out;

public interface GithubOAuthPort {
    String getAuthorizeUrl(String state);
    String getAccessToken(String code);
}
