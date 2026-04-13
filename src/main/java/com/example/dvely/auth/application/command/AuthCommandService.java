package com.example.dvely.auth.application.command;

import com.example.dvely.auth.application.command.dto.GithubLoginCommand;
import com.example.dvely.auth.application.command.dto.TokenResult;
import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.application.port.out.GithubUserPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.service.AuthDomainService;
import com.example.dvely.auth.domain.value.GithubId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthCommandService {

    private final GithubOAuthPort githubOAuthPort;
    private final GithubUserPort githubUserPort;
    private final GithubAppPort githubAppPort;
    private final AuthDomainService authDomainService;
    private final UserRepository userRepository;
    private final TokenPort tokenPort;

    /**
     * GitHub OAuth App + GitHub App 통합 로그인
     *
     * 흐름:
     * 1. [OAuth App] code → OAuth User Access Token
     * 2. [OAuth App] 유저 정보 조회 (id, login)
     * 3. DB에서 유저 찾기 or 신규 생성
     * 4. [GitHub App] 유저의 App 설치 여부 확인 → installation_id 저장
     * 5. 저장 후 서비스 JWT 발급
     */
    @Transactional
    public TokenResult loginWithGithub(GithubLoginCommand command) {
        // 1. Authorization Code → OAuth User Access Token
        String oauthToken = githubOAuthPort.getAccessToken(command.code());

        // 2. OAuth 토큰으로 GitHub 유저 정보 조회
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(oauthToken);

        // 3. 유저 findOrCreate
        GithubId githubId = new GithubId(githubUser.id());
        User user = userRepository.findByGithubId(githubId)
                .map(existing -> {
                    authDomainService.updateUsername(existing, githubUser.login());
                    return existing;
                })
                .orElseGet(() -> authDomainService.createUser(githubId, githubUser.login()));

        // 4. GitHub App 설치 여부 확인 (실패해도 로그인 자체는 성공)
        Optional<Long> installationId = githubAppPort.findInstallationId(oauthToken);
        installationId.ifPresentOrElse(
                id -> {
                    authDomainService.updateInstallationId(user, id);
                    log.info("GitHub App 설치 확인: user={}, installationId={}", githubUser.login(), id);
                },
                () -> log.info("GitHub App 미설치: user={}", githubUser.login())
        );

        // 5. 저장
        User savedUser = userRepository.save(user);

        // 6. 서비스 JWT 발급
        String jwt = tokenPort.createToken(savedUser.getId());

        return new TokenResult(jwt, savedUser.hasGithubAppInstalled());
    }

    /**
     * GitHub App 설치 완료 콜백 처리
     * GitHub App 설치 후 GitHub이 installation_id와 함께 리다이렉트
     */
    @Transactional
    public void linkGithubApp(Long userId, Long installationId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다: " + userId));

        authDomainService.updateInstallationId(user, installationId);
        userRepository.save(user);

        log.info("GitHub App 연동 완료: userId={}, installationId={}", userId, installationId);
    }
}
