package com.example.dvely.auth.domain.repository;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByGithubId(GithubId githubId);
    User save(User user);
}
