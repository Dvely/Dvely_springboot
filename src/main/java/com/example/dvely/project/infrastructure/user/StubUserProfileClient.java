package com.example.dvely.project.infrastructure.user;

import com.example.dvely.project.application.port.out.UserProfilePort;
import org.springframework.stereotype.Component;

@Component
public class StubUserProfileClient implements UserProfilePort {

    @Override
    public String getGithubLogin(Long userId) {
        // TODO: user 모듈 완료 후 실제 사용자 프로필 조회로 교체
        return "user" + userId;
    }
}
