package com.example.dvely.auth.application.query;

import com.example.dvely.auth.application.command.dto.LoginUrlResult;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.infrastructure.oauth.OAuthStateManager;
import com.example.dvely.common.exception.ForbiddenException;
import com.example.dvely.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthQueryService {

    private final GithubOAuthPort githubOAuthPort;
    private final GithubAppPort githubAppPort;
    private final UserRepository userRepository;
    private final OAuthStateManager oAuthStateManager;

    public LoginUrlResult getGithubLoginUrl() {
        String state = oAuthStateManager.generate();
        String url = githubOAuthPort.getAuthorizeUrl(state);
        return new LoginUrlResult(url, state);
    }

    /**
     * @param userToken 현재 로그인한 유저의 서비스 JWT
     *                  GitHub 설치 완료 후 콜백에서 유저 식별에 사용됨
     */
    public String getGithubAppInstallUrl(String userToken) {
        return githubAppPort.getInstallationUrl(userToken);
    }

    /**
     * 이미 설치된 GitHub App의 User Token만 재발급받는 URL 반환
     * App 재설치 없이 만료된 User Token을 갱신할 때 사용
     *
     * @param userId    현재 로그인한 유저 ID
     * @param userToken 콜백에서 유저 식별에 사용할 서비스 JWT
     */
    public String getGithubAppReauthorizeUrl(Long userId, String userToken) {
        Long installationId = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + userId))
                .getGithubInstallationId();

        if (installationId == null) {
            throw new ForbiddenException("GitHub App이 설치되지 않았습니다. 먼저 App을 설치해 주세요.");
        }

        return githubAppPort.getReauthorizeUrl(installationId, userToken);
    }
}
