package com.example.dvely.auth.application.command;

import com.example.dvely.auth.application.command.dto.GithubLoginCommand;
import com.example.dvely.auth.application.command.dto.TokenResult;
import com.example.dvely.auth.application.port.out.GithubOAuthPort;
import com.example.dvely.auth.application.port.out.GithubUserPort;
import com.example.dvely.auth.application.port.out.TokenPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.service.AuthDomainService;
import com.example.dvely.auth.domain.value.GithubId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthCommandService {

    private final GithubOAuthPort githubOAuthPort;
    private final GithubUserPort githubUserPort;
    private final AuthDomainService authDomainService;
    private final UserRepository userRepository;
    private final TokenPort tokenPort;

    @Transactional
    public TokenResult loginWithGithub(GithubLoginCommand command) {
        // 1. access_token 요청
        String accessToken = githubOAuthPort.getAccessToken(command.code());

        // 2. GitHub 유저 조회
        GithubUserPort.GithubUserInfo githubUser = githubUserPort.getUser(accessToken);

        // 3. 도메인 처리 (findOrCreate)
        GithubId githubId = new GithubId(githubUser.id());
        User user = userRepository.findByGithubId(githubId)
                .map(existing -> {
                    authDomainService.updateUsername(existing, githubUser.login());
                    return existing;
                })
                .orElseGet(() -> authDomainService.createUser(githubId, githubUser.login()));

        // 4. 저장
        User savedUser = userRepository.save(user);

        // 5. JWT 발급
        String token = tokenPort.createToken(savedUser.getId());

        return new TokenResult(token);
    }
}
