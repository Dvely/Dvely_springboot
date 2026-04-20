package com.example.dvely.auth.domain.service;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;
import org.springframework.stereotype.Component;

@Component
public class AuthDomainService {

    public User createUser(GithubId githubId, String username, String avatarUrl) {
        return new User(githubId, username, avatarUrl);
    }

    public void updateProfile(User user, String username, String avatarUrl) {
        user.updateProfile(username, avatarUrl);
    }

    public void updateInstallationId(User user, Long installationId) {
        user.updateInstallationId(installationId);
    }
}
