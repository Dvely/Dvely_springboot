package com.example.dvely.project.infrastructure.user;

import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.project.application.port.out.UserProfilePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserProfileClient implements UserProfilePort {

    private final UserRepository userRepository;

    @Override
    public String getGithubLogin(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다: " + userId));
    }
}
