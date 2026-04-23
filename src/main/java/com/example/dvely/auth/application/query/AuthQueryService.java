package com.example.dvely.auth.application.query;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthQueryService {

    private final GithubOAuthPort githubOAuthPort;
    private final GithubAppPort githubAppPort;

    public String getGithubLoginUrl() {
        return githubOAuthPort.getAuthorizeUrl();
    }

    /**
     * @param userToken 현재 로그인한 유저의 서비스 JWT
     *                  GitHub 설치 완료 후 콜백에서 유저 식별에 사용됨
     */
    public String getGithubAppInstallUrl(String userToken) {
        return githubAppPort.getInstallationUrl(userToken);
    }
}
