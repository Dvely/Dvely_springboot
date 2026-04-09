package com.example.dvely.auth.domain.service;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;
import org.springframework.stereotype.Component;

@Component
public class AuthDomainService {

    public User createUser(GithubId githubId, String username) {
        return new User(githubId, username);
    }

    public void updateUsername(User user, String username) {
        user.updateUsername(username);
    }
}
